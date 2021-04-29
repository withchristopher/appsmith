import { AppState } from "reducers";
import { get } from "lodash";
import { getCurrentUser } from "selectors/usersSelectors";
import { CommentThread } from "entities/Comments/CommentsInterfaces";

export const refCommentThreadsSelector = (
  refId: string,
  applicationId?: string,
) => (state: AppState) =>
  get(
    state.ui.comments.applicationCommentThreadsByRef,
    `${applicationId}.${refId}`,
    [],
  );

export const commentThreadsSelector = (commentThreadId: string) => (
  state: AppState,
) => state.ui.comments.commentThreadsMap[commentThreadId];

export const unpublishedCommentThreadSelector = (refId: string) => (
  state: AppState,
) => state.ui.comments.unpublishedCommentThreads[refId];

export const commentModeSelector = (state: AppState) =>
  state.ui.comments?.isCommentMode;

export const applicationCommentsSelector = (applicationId: string) => (
  state: AppState,
) => state.ui.comments.applicationCommentThreadsByRef[applicationId];

export const areCommentsEnabledForUser = (state: AppState) => {
  const user = getCurrentUser(state);
  const email = get(user, "email", "");
  const isAppsmithEmail = email.toLowerCase().indexOf("@appsmith.com") !== -1;
  return isAppsmithEmail;
};

/**
 * Comments are stored as a map of refs (for example widgetIds)
 * Flatten to fetch all application comment threads
 */
export const getAppCommentThreads = (
  threadsByRefMap: Record<string, Array<string>>,
): Array<string> => {
  if (!threadsByRefMap) return [];
  return Object.entries(threadsByRefMap).reduce(
    (res: Array<string>, [, threadIds]) => {
      return [...res, ...threadIds];
    },
    [],
  );
};

export const allCommentThreadsMap = (state: AppState) =>
  state.ui.comments.commentThreadsMap;

const getSortIndexBool = (a: boolean, b: boolean) => {
  if (a && b) return 0;
  if (a) return -1;
  if (b) return 1;
  else return 0;
};

const getSortIndexNumber = (a = 0, b = 0) => {
  if (a === b) return 0;
  if (a > b) return -1;
  else return 1;
};

export const getSortedAppCommentThreadIds = (
  applicationThreadIds: Array<string>,
  commentThreadsMap: Record<string, CommentThread>,
  shouldShowResolved: boolean,
): Array<string> => {
  if (!applicationThreadIds) return [];
  return applicationThreadIds
    .sort((a, b) => {
      const {
        pinnedState: isAPinned,
        updationTime: updationTimeA,
      } = commentThreadsMap[a];
      const {
        pinnedState: isBPinned,
        updationTime: updationTimeB,
      } = commentThreadsMap[b];

      const sortIdx = getSortIndexBool(
        !!isAPinned?.active,
        !!isBPinned?.active,
      );
      if (sortIdx !== 0) return sortIdx;

      return getSortIndexNumber(
        updationTimeA?.epochSecond,
        updationTimeB?.epochSecond,
      );
    })
    .filter((threadId: string) => {
      const thread = commentThreadsMap[threadId];
      const shouldShow = shouldShowResolved || !thread.resolvedState?.active;
      return shouldShow;
    });
};

export const shouldShowResolved = (state: AppState) =>
  state.ui.comments.shouldShowResolvedAppCommentThreads;

export const appCommentsFilter = (state: AppState) =>
  state.ui.comments.appCommentsFilter;

export const showUnreadIndicator = (state: AppState) =>
  state.ui.comments.showUnreadIndicator;

export const visibleCommentThread = (state: AppState) =>
  state.ui.comments.visibleCommentThreadId;