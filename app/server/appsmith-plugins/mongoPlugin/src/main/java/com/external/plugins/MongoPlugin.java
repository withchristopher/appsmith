package com.external.plugins;

import com.appsmith.external.models.ActionConfiguration;
import com.appsmith.external.models.ActionExecutionResult;
import com.appsmith.external.models.AuthenticationDTO;
import com.appsmith.external.models.Connection;
import com.appsmith.external.models.DatasourceConfiguration;
import com.appsmith.external.models.DatasourceStructure;
import com.appsmith.external.models.DatasourceTestResult;
import com.appsmith.external.models.Endpoint;
import com.appsmith.external.models.SSLDetails;
import com.appsmith.external.pluginExceptions.AppsmithPluginError;
import com.appsmith.external.pluginExceptions.AppsmithPluginException;
import com.appsmith.external.pluginExceptions.StaleConnectionException;
import com.appsmith.external.plugins.BasePlugin;
import com.appsmith.external.plugins.PluginExecutor;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.pf4j.Extension;
import org.pf4j.PluginWrapper;
import org.reactivestreams.Publisher;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MongoPlugin extends BasePlugin {

    private static final Set<AuthenticationDTO.Type> VALID_AUTH_TYPES = Set.of(
            AuthenticationDTO.Type.SCRAM_SHA_1,
            AuthenticationDTO.Type.SCRAM_SHA_256,
            AuthenticationDTO.Type.MONGODB_CR  // NOTE: Deprecated in the driver.
    );

    private static final String VALID_AUTH_TYPES_STR = VALID_AUTH_TYPES.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(", "));

    private static final int DEFAULT_PORT = 27017;

    public static final String N_MODIFIED = "nModified";

    private static final String VALUE_STR = "value";

    public MongoPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Slf4j
    @Extension
    public static class MongoPluginExecutor implements PluginExecutor<MongoClient> {

        /**
         * For reference on creating the json queries for Mongo please head to
         * https://docs.huihoo.com/mongodb/3.4/reference/command/index.html
         *
         * @param mongoClient             : This is the connection that is established to the data source. This connection is according
         *                                to the parameters in Datasource Configuration
         * @param datasourceConfiguration : These are the configurations which have been used to create a Datasource from a Plugin
         * @param actionConfiguration     : These are the configurations which have been used to create an Action from a Datasource.
         * @return Result data from executing the action's query.
         */
        @Override
        public Mono<ActionExecutionResult> execute(MongoClient mongoClient,
                                                   DatasourceConfiguration datasourceConfiguration,
                                                   ActionConfiguration actionConfiguration) {

            if (mongoClient == null) {
                log.info("Encountered null connection in MongoDB plugin. Reporting back.");
                throw new StaleConnectionException();
            }

            MongoDatabase database = mongoClient.getDatabase(getDatabaseName(datasourceConfiguration));
            Bson command = Document.parse(actionConfiguration.getBody());

            // Execute the mongo db command now
            Publisher<Document> mongoOutputPublisher = database.runCommand(command);

//            Mono<Document> mongoOutputMono = Mono.empty();
//            if (mongoOutputPublisher.getClass().equals(Mono.class)) {
//                mongoOutputMono = Mono.from(mongoOutputPublisher);
//            } else if (mongoOutputPublisher.getClass().equals(Flux.class)) {
//                /**
//                 * TODO : Check if this is even required. Its quite possible that mongo db would still return a Document as a result
//                 * and not Flux of Documents.
//                 */
//                Flux object = Flux.from(mongoOutputPublisher);
//                mongoOutputMono = object.collectList();
//            }

            Mono<Document> mongoOutputMono = Mono.from(mongoOutputPublisher);

            // Parse the output from the execution and set it in Appsmith specific structure before returning back to Appsmith Server
            return mongoOutputMono
                    .flatMap(mongoOutput -> {
                        ActionExecutionResult result = new ActionExecutionResult();
                        JSONObject outputJson = new JSONObject(mongoOutput.toJson());
                        //The output json contains the key "ok". This is the status of the command
                        BigInteger status = outputJson.getBigInteger("ok");
                        JSONArray headerArray = new JSONArray();

                        if (BigInteger.ONE.equals(status)) {
                            result.setIsExecutionSuccess(true);

                            // For the `findAndModify` command, we don't get the count of modifications made. Instead, we either
                            // get the modified new value or the pre-modified old value (depending on the `new` field in the
                            // command. Let's return that value to the user.
                            if (outputJson.has(VALUE_STR)) {
                                try {
                                    result.setBody(objectMapper.readTree(
                                            cleanUp(new JSONObject().put(VALUE_STR, outputJson.get(VALUE_STR))).toString()
                                    ));
                                } catch (IOException e) {
                                    return Mono.error(new AppsmithPluginException(AppsmithPluginError.PLUGIN_ERROR));
                                }
                            }

                            //The json contains key "cursor" when find command was issued and there are 1 or more results. In case
                            //there are no results for find, this key is not present in the result json.
                            if (outputJson.has("cursor")) {
                                JSONArray outputResult = (JSONArray) cleanUp(
                                        outputJson.getJSONObject("cursor").getJSONArray("firstBatch"));
                                try {
                                    result.setBody(objectMapper.readTree(outputResult.toString()));
                                } catch (IOException e) {
                                    return Mono.error(new AppsmithPluginException(AppsmithPluginError.PLUGIN_ERROR));
                                }
                            }

                            //The json contains key "n" when insert/update command is issued. "n" for update signifies the no of
                            //documents selected for update. "n" in case of insert signifies the number of documents inserted.
                            if (outputJson.has("n")) {
                                JSONObject body = new JSONObject().put("n", outputJson.getBigInteger("n"));
                                result.setBody(body);
                                headerArray.put(body);
                            }

                            //The json key contains key "nModified" in case of update command. This signifies the no of
                            //documents updated.
                            if (outputJson.has(N_MODIFIED)) {
                                JSONObject body = new JSONObject().put(N_MODIFIED, outputJson.getBigInteger(N_MODIFIED));
                                result.setBody(body);
                                headerArray.put(body);
                            }

                            /** TODO
                             * Go through all the possible fields that are returned in the output JSON and add all the fields
                             * that are important to the headerArray.
                             */
                        }

                        JSONObject statusJson = new JSONObject().put("ok", status);
                        headerArray.put(statusJson);
                        try {
                            result.setHeaders(objectMapper.readTree(headerArray.toString()));
                        } catch (IOException e) {
                            return Mono.error(new AppsmithPluginException(AppsmithPluginError.PLUGIN_ERROR));
                        }

                        return Mono.just(result);
                    });

        }

        private String getDatabaseName(DatasourceConfiguration datasourceConfiguration) {
            // Explicitly set default database.
            String databaseName = datasourceConfiguration.getConnection().getDefaultDatabaseName();

            // If that's not available, pick the authentication database.
            final AuthenticationDTO authentication = datasourceConfiguration.getAuthentication();
            if (StringUtils.isEmpty(databaseName) && authentication != null) {
                databaseName = authentication.getDatabaseName();
            }

            return databaseName;
        }

        @Override
        public Mono<MongoClient> datasourceCreate(DatasourceConfiguration datasourceConfiguration) {
            // TODO: ReadOnly seems to be not supported at the driver level. The recommendation is to connect with a
            //   user that doesn't have write permissions on the database.
            //   Ref: https://api.mongodb.com/java/2.13/com/mongodb/DB.html#setReadOnly-java.lang.Boolean-

            try {
                //return Mono.just(new MongoClient(buildClientURI(datasourceConfiguration)));
                return Mono.just(MongoClients.create(buildClientURI(datasourceConfiguration)));
            } catch (Exception e) {
                return Mono.error(new AppsmithPluginException(AppsmithPluginError.PLUGIN_ERROR, e));
            }
        }

        //public static MongoClientURI buildClientURI(DatasourceConfiguration datasourceConfiguration) {
        public static String buildClientURI(DatasourceConfiguration datasourceConfiguration) {
            StringBuilder builder = new StringBuilder();

            final Connection connection = datasourceConfiguration.getConnection();
            final List<Endpoint> endpoints = datasourceConfiguration.getEndpoints();

            // Use SRV mode if using REPLICA_SET, AND a port is not specified in the first endpoint. In SRV mode, the
            // host and port details of individual shards will be obtained from the TXT records of the first endpoint.
            boolean isSrv = Connection.Type.REPLICA_SET.equals(connection.getType())
                    && endpoints.get(0).getPort() == null;

            if (isSrv) {
                builder.append("mongodb+srv://");
            } else {
                builder.append("mongodb://");
            }

            AuthenticationDTO authentication = datasourceConfiguration.getAuthentication();
            if (authentication != null) {
                builder
                        .append(urlEncode(authentication.getUsername()))
                        .append(':')
                        .append(urlEncode(authentication.getPassword()))
                        .append('@');
            }

            for (Endpoint endpoint : endpoints) {
                builder.append(endpoint.getHost());
                if (endpoint.getPort() != null) {
                    builder.append(':').append(endpoint.getPort());
                } else if (!isSrv) {
                    // Connections with +srv should NOT have a port.
                    builder.append(':').append(DEFAULT_PORT);
                }
                builder.append(',');
            }

            // Delete the trailing comma.
            builder.deleteCharAt(builder.length() - 1);

            final String authenticationDatabaseName = authentication == null ? null : authentication.getDatabaseName();
            builder.append('/').append(authenticationDatabaseName);

            List<String> queryParams = new ArrayList<>();

            final SSLDetails sslDetails = connection.getSsl();
            if (sslDetails != null && !SSLDetails.AuthType.NO_SSL.equals(sslDetails.getAuthType())) {
                queryParams.add("ssl=true");
            }

            if (authentication != null && authentication.getAuthType() != null) {
                queryParams.add("authMechanism=" + authentication.getAuthType().name().replace('_', '-'));
            }

            if (!queryParams.isEmpty()) {
                builder.append('?');
                for (String param : queryParams) {
                    builder.append(param).append('&');
                }
                // Delete the trailing ampersand.
                builder.deleteCharAt(builder.length() - 1);
            }

            return builder.toString();
            /*final String uri = builder.toString();
            log.info("MongoPlugin URI: `{}`.", uri);
            return new MongoClientURI(uri);*/
        }

        @Override
        public void datasourceDestroy(MongoClient mongoClient) {
            if (mongoClient != null) {
                mongoClient.close();
            }
        }

        @Override
        public Set<String> validateDatasource(DatasourceConfiguration datasourceConfiguration) {
            Set<String> invalids = new HashSet<>();

            List<Endpoint> endpoints = datasourceConfiguration.getEndpoints();
            if (CollectionUtils.isEmpty(endpoints)) {
                invalids.add("Missing endpoint(s).");

            } else if (Connection.Type.REPLICA_SET.equals(datasourceConfiguration.getConnection().getType())) {
                if (endpoints.size() == 1 && endpoints.get(0).getPort() != null) {
                    invalids.add("REPLICA_SET connections should not be given a port." +
                            " If you are trying to specify all the shards, please add more than one.");
                }

            }

            AuthenticationDTO authentication = datasourceConfiguration.getAuthentication();
            if (authentication == null) {
                invalids.add("Missing authentication details.");

            } else {
                AuthenticationDTO.Type authType = authentication.getAuthType();

                if (authType != null && VALID_AUTH_TYPES.contains(authType)) {

                    if (StringUtils.isEmpty(authentication.getUsername())) {
                        invalids.add("Missing username for authentication. Needed because authType is " + authType + ".");
                    }

                    if (StringUtils.isEmpty(authentication.getPassword())) {
                        invalids.add("Missing password for authentication. Needed because authType is " + authType + ".");
                    }

                } else {
                    invalids.add("Invalid authType. Must be one of " + VALID_AUTH_TYPES_STR);

                }

                if (StringUtils.isEmpty(authentication.getDatabaseName())) {
                    invalids.add("Missing database name.");
                }

            }

            return invalids;
        }

        @Override
        public Mono<DatasourceTestResult> testDatasource(DatasourceConfiguration datasourceConfiguration) {
            final Connection.Type connectionType = datasourceConfiguration.getConnection().getType();
            return datasourceCreate(datasourceConfiguration)
                    .flatMap(mongoClient -> {
                        //ClientSession clientSession = null;
                        Mono<ClientSession> clientSession = null;

                        try {
                            // Not using try-with-resources here since we want to close the *session* before closing the
                            // MongoClient instance.
                            if (Connection.Type.REPLICA_SET.equals(connectionType)) {
                                // For REPLICA_SET connections, we check by creating a session, as this is faster.
                                //clientSession = mongoClient.startSession();
                                clientSession = Mono.from(mongoClient.startSession());

                            } else {
                                // For DIRECT connections, we check by running a DB command, as it's the only reliable
                                // method of checking if the connection is usable.
                                Mono outputMono = (Mono) mongoClient
                                        .getDatabase("admin")
                                        .runCommand(new Document("listDatabases", 1));

                                return outputMono
                                        .then(Mono.just(new DatasourceTestResult()));

                            }

                        } catch (MongoTimeoutException e) {
                            log.warn("Timeout connecting to MongoDB from MongoPlugin.", e);
                            return Mono.just(new DatasourceTestResult("Timed out trying to connect to MongoDB host."));

                        } catch (MongoCommandException e) {
                            // The fact that we got a response saying "Unauthorized" means that the connection to the
                            // MongoDB instance is valid. It also means we don't have access to the admin database, but
                            // that's okay for our purposes here.
                            return "Unauthorized".equals(e.getErrorCodeName())
                                    ? Mono.just(new DatasourceTestResult())
                                    : Mono.just(new DatasourceTestResult(e.getMessage()));

                        } catch (Exception e) {
                            return Mono.just(new DatasourceTestResult(e.getMessage()));

                        } finally {
                            if (clientSession != null) {
                                //clientSession.close();
                                // TODO: ??
                            }
                            if (mongoClient != null) {
                                mongoClient.close();
                            }

                        }

                        return Mono.just(new DatasourceTestResult());
                    })
                    .onErrorResume(error -> {
                        Exception e = (Exception) error;
                        return Mono.just(new DatasourceTestResult(e.getMessage()));
                    });
        }

        @Override
        public Mono<DatasourceStructure> getStructure(MongoClient mongoClient, DatasourceConfiguration datasourceConfiguration) {

            String databaseName = getDatabaseName(datasourceConfiguration);
            final MongoDatabase database = mongoClient.getDatabase(databaseName);

            final DatasourceStructure structure = new DatasourceStructure();
            List<DatasourceStructure.Table> tables = new ArrayList<>();
            structure.setTables(tables);

            final ArrayList<DatasourceStructure.Column> columns = new ArrayList<>();
            final ArrayList<DatasourceStructure.Template> templates = new ArrayList<>();

            return Mono.just(database)
                    .flatMapMany(db -> {
                        Mono<List<String>> collectionNamesMono = Mono.empty();
                        Publisher<String> listCollectionNamesPublisher = db.listCollectionNames();

                        // Assumption is that the returned publisher type would be of type flux and not mono. Code handling
                        // in the next block is due to this assumption
                        if (listCollectionNamesPublisher.getClass().equals(Mono.class)) {
                            collectionNamesMono = (Mono) listCollectionNamesPublisher;
                        } else if (listCollectionNamesPublisher.getClass().equals(Flux.class)) {
                            Flux collectionNamesFlux = (Flux) listCollectionNamesPublisher;
                            collectionNamesMono = collectionNamesFlux.collectList();
                        }

                        return collectionNamesMono
                                .flatMapMany(Flux::fromIterable)
                                .flatMap(collectionName -> {
                                    tables.add(new DatasourceStructure.Table(
                                            DatasourceStructure.TableType.COLLECTION,
                                            collectionName,
                                            columns,
                                            new ArrayList<>(),
                                            templates
                                    ));

                                    return database.getCollection(collectionName).find().limit(1).first();
                                    /**
                                     * TODO : In case the first document fetched does not have any data, skip the collection. Handle this scenario
                                     * in case its not handled automatically by mongo returning Mono.void
                                     */
                                })
                                .map(document -> {
                                    /**
                                     * TODO : Check if the below statement even works
                                     */
                                    generateTemplatesAndStructureForACollection(document, columns, templates);
                                    return document;
                                });
                    })
                    .collectList()
                    .thenReturn(tables)
                    .map(documents -> {
                        log.debug("Added structures of {} collections to the Datasource Structure Table", documents.size());
                        tables.sort(Comparator.comparing(DatasourceStructure.Table::getName));
                        return structure;
                    });
        }

    }


    private static void generateTemplatesAndStructureForACollection(Document document,
                                                                    ArrayList<DatasourceStructure.Column> columns,
                                                                    ArrayList<DatasourceStructure.Template> templates) {
        String collectionName = (String) document.get("name");
        String filterFieldName = null;
        String filterFieldValue = null;
        Map<String, String> sampleInsertValues = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : document.entrySet()) {
            final String name = entry.getKey();
            final Object value = entry.getValue();
            String type;

            if (value instanceof Integer) {
                type = "Integer";
                sampleInsertValues.put(name, "1");
            } else if (value instanceof Long) {
                type = "Long";
                sampleInsertValues.put(name, "NumberLong(\"1\")");
            } else if (value instanceof Double) {
                type = "Double";
                sampleInsertValues.put(name, "1");
            } else if (value instanceof Decimal128) {
                type = "BigDecimal";
                sampleInsertValues.put(name, "NumberDecimal(\"1\")");
            } else if (value instanceof String) {
                type = "String";
                sampleInsertValues.put(name, "\"new value\"");
                if (filterFieldName == null || filterFieldName.compareTo(name) > 0) {
                    filterFieldName = name;
                    filterFieldValue = (String) value;
                }
            } else if (value instanceof ObjectId) {
                type = "ObjectId";
                if (!value.equals("_id")) {
                    sampleInsertValues.put(name, "ObjectId(\"a_valid_object_id_hex\")");
                }
            } else if (value instanceof Collection) {
                type = "Array";
                sampleInsertValues.put(name, "[1, 2, 3]");
            } else if (value instanceof Date) {
                type = "Date";
                sampleInsertValues.put(name, "new Date(\"2019-07-01\")");
            } else {
                type = "Object";
                sampleInsertValues.put(name, "{}");
            }

            columns.add(new DatasourceStructure.Column(name, type, null));
        }

        columns.sort(Comparator.naturalOrder());

        templates.add(
                new DatasourceStructure.Template(
                        "Find",
                        "{\n" +
                                "  \"find\": \"" + collectionName + "\",\n" +
                                (
                                        filterFieldName == null ? "" :
                                                "  \"filter\": {\n" +
                                                        "    \"" + filterFieldName + "\": \"" + filterFieldValue + "\"\n" +
                                                        "  },\n"
                                ) +
                                "  \"sort\": {\n" +
                                "    \"_id\": 1\n" +
                                "  },\n" +
                                "  \"limit\": 10\n" +
                                "}\n"
                )
        );

        templates.add(
                new DatasourceStructure.Template(
                        "Find by ID",
                        "{\n" +
                                "  \"find\": \"" + collectionName + "\",\n" +
                                "  \"filter\": {\n" +
                                "    \"_id\": ObjectId(\"id_to_query_with\")\n" +
                                "  }\n" +
                                "}\n"
                )
        );

        sampleInsertValues.entrySet().stream()
                .map(entry -> "      \"" + entry.getKey() + "\": " + entry.getValue() + ",\n")
                .collect(Collectors.joining(""));
        templates.add(
                new DatasourceStructure.Template(
                        "Insert",
                        "{\n" +
                                "  \"insert\": \"" + collectionName + "\",\n" +
                                "  \"documents\": [\n" +
                                "    {\n" +
                                sampleInsertValues.entrySet().stream()
                                        .map(entry -> "      \"" + entry.getKey() + "\": " + entry.getValue() + ",\n")
                                        .sorted()
                                        .collect(Collectors.joining("")) +
                                "    }\n" +
                                "  ]\n" +
                                "}\n"
                )
        );

        templates.add(
                new DatasourceStructure.Template(
                        "Update",
                        "{\n" +
                                "  \"update\": \"" + collectionName + "\",\n" +
                                "  \"updates\": [\n" +
                                "    {\n" +
                                "      \"q\": {\n" +
                                "        \"_id\": ObjectId(\"id_of_document_to_update\")\n" +
                                "      },\n" +
                                "      \"u\": { \"$set\": { \"" + filterFieldName + "\": \"new value\" } }\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}\n"
                )
        );

        templates.add(
                new DatasourceStructure.Template(
                        "Delete",
                        "{\n" +
                                "  \"delete\": \"" + collectionName + "\",\n" +
                                "  \"deletes\": [\n" +
                                "    {\n" +
                                "      \"q\": {\n" +
                                "        \"_id\": \"id_of_document_to_delete\"\n" +
                                "      },\n" +
                                "      \"limit\": 1\n" +
                                "    }\n" +
                                "  ]\n" +
                                "}\n"
                )
        );
    }

    private static String urlEncode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static Object cleanUp(Object object) {
        if (object instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) object;
            final boolean isSingleKey = jsonObject.keySet().size() == 1;

            if (isSingleKey && "$numberLong".equals(jsonObject.keys().next())) {
                return jsonObject.getBigInteger("$numberLong");

            } else if (isSingleKey && "$oid".equals(jsonObject.keys().next())) {
                return jsonObject.getString("$oid");

            } else if (isSingleKey && "$date".equals(jsonObject.keys().next())) {
                return DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(jsonObject.getLong("$date"))
                );

            } else if (isSingleKey && "$numberDecimal".equals(jsonObject.keys().next())) {
                return new BigDecimal(jsonObject.getString("$numberDecimal"));

            } else {
                for (String key : new HashSet<>(jsonObject.keySet())) {
                    jsonObject.put(key, cleanUp(jsonObject.get(key)));
                }

            }

        } else if (object instanceof JSONArray) {
            Collection<Object> cleaned = new ArrayList<>();

            for (Object child : (JSONArray) object) {
                cleaned.add(cleanUp(child));
            }

            return new JSONArray(cleaned);

        }

        return object;
    }

}
