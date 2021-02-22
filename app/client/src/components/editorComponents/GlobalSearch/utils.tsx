export enum SEARCH_ITEM_TYPES {
  documentation = "documentation",
  action = "action",
  widget = "widget",
  datasource = "datasource",
}

// todo better checks here?
export const getItemType = (item: any): SEARCH_ITEM_TYPES => {
  let type: SEARCH_ITEM_TYPES;
  if (item.widgetName) type = SEARCH_ITEM_TYPES.widget;
  else if (item.kind === "document") type = SEARCH_ITEM_TYPES.documentation;
  else if (item.config?.name) type = SEARCH_ITEM_TYPES.action;
  else type = SEARCH_ITEM_TYPES.datasource;

  return type;
};

export const getItemTitle = (item: any): string => {
  const type = getItemType(item);

  switch (type) {
    case "action":
      return item?.config?.name;
    case "widget":
      return item?.widgetName;
    case "datasource":
      return item?.name;
    default:
      return "";
  }
};

const defaultDocs = [
  {
    link:
      "https://raw.githubusercontent.com/appsmithorg/appsmith-docs/v1.2.1/tutorial-1/README.md",
    title: "Tutorial",
    path: "master/tutorial-1",
    kind: "document",
  },
  {
    link:
      "https://raw.githubusercontent.com/appsmithorg/appsmith-docs/v1.2.1/core-concepts/connecting-to-data-sources/README.md",
    title: "Connecting to Data Sources",
    path: "master/core-concepts/connecting-to-data-sources",
    kind: "document",
  },
  {
    link:
      "https://raw.githubusercontent.com/appsmithorg/appsmith-docs/v1.2.1/core-concepts/displaying-data-read/README.md",
    title: "Displaying Data (Read)",
    path: "master/core-concepts/displaying-data-read",
    kind: "document",
  },
  {
    link:
      "https://raw.githubusercontent.com/appsmithorg/appsmith-docs/v1.2.1/core-concepts/writing-code/README.md",
    title: "Writing Code",
    path: "master/core-concepts/writing-code",
    kind: "document",
  },
];

export const getDefaultDocumentationResults = async () => {
  const data = await Promise.all(
    defaultDocs.map(async (doc) => {
      const response = await fetch(doc.link);
      const document = await response.text();
      return {
        _highlightResult: {
          document: {
            value: document,
          },
          title: {
            value: doc.title,
          },
        },
        ...doc,
      };
    }),
  );

  return data;
};
