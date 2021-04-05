import React, { useEffect } from "react";
import styled from "styled-components";
import { useDispatch, useSelector } from "react-redux";
import { ReactComponent as ToggleCommmentMode } from "assets/icons/comments/toggle-comment-mode.svg";
import {
  setCommentMode as setCommentModeAction,
  fetchApplicationCommentsRequest,
} from "actions/commentActions";
import {
  commentModeSelector,
  areCommentsEnabledForUser as areCommentsEnabledForUserSelector,
} from "./selectors";

const StyledToggleCommentMode = styled.div<{ isCommentMode: boolean }>`
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  background: ${(props) =>
    !props.isCommentMode
      ? props.theme.colors.comments.commentModeButtonBackground
      : props.theme.colors.comments.commentModeButtonIcon};
  svg path {
    fill: ${(props) =>
      props.isCommentMode
        ? "#fff"
        : props.theme.colors.comments.commentModeButtonIcon};
  }

  height: ${(props) => props.theme.smallHeaderHeight};
  width: ${(props) => props.theme.smallHeaderHeight};
`;

/**
 * Toggle comment mode:
 * This component is also responsible for fetching
 * application comments
 */
const ToggleCommentModeButton = () => {
  const dispatch = useDispatch();
  const commentsEnabled = useSelector(areCommentsEnabledForUserSelector);
  const isCommentMode = useSelector(commentModeSelector);
  const setCommentMode = () => dispatch(setCommentModeAction(!isCommentMode));

  useEffect(() => {
    if (isCommentMode) {
      dispatch(fetchApplicationCommentsRequest());
    }
  }, [isCommentMode]);

  return commentsEnabled ? (
    <StyledToggleCommentMode
      onClick={setCommentMode}
      isCommentMode={isCommentMode}
    >
      <ToggleCommmentMode />
    </StyledToggleCommentMode>
  ) : null;
};

export default ToggleCommentModeButton;
