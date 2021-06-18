import React, { useEffect, useCallback } from "react";
import { useDispatch, useSelector } from "react-redux";
import { IconWrapper } from "constants/IconConstants";
import styled from "styled-components";
import { Colors } from "constants/Colors";
import { ReactComponent as FilterIcon } from "assets/icons/control/filter-icon.svg";
import { TableIconWrapper } from "components/designSystems/appsmith/TableComponent/TableStyledWrappers";
import TableAction from "components/designSystems/appsmith/TableComponent/TableAction";
import TableFilterPane from "components/designSystems/appsmith/TableComponent/TableFilterPane";
import {
  ReactTableColumnProps,
  ReactTableFilter,
  OperatorTypes,
} from "components/designSystems/appsmith/TableComponent/Constants";
import { hidePropertyPane } from "actions/propertyPaneActions";
import { ReduxActionTypes } from "constants/ReduxActionConstants";
import { generateClassName } from "utils/generators";
import { getTableFilterState } from "selectors/tableFilterSelectors";

const SelectedFilterWrapper = styled.div`
  position: absolute;
  top: 12px;
  right: 12px;
  background: ${Colors.GREEN};
  border: 0.5px solid ${Colors.WHITE};
  box-sizing: border-box;
  border-radius: 50%;
  width: 10px;
  height: 10px;
  display: flex;
  justify-content: center;
  align-items: center;
  font-weight: bold;
  font-size: 6px;
  color: ${Colors.WHITE};
`;

export interface DropdownOption {
  label: string;
  value: string;
  type: string;
}
interface TableFilterProps {
  columns: ReactTableColumnProps[];
  filters?: ReactTableFilter[];
  applyFilter: (filters: ReactTableFilter[]) => void;
  editMode: boolean;
  widgetId: string;
}

function TableFilters(props: TableFilterProps) {
  const [filters, updateFilters] = React.useState(
    new Array<ReactTableFilter>(),
  );

  const dispatch = useDispatch();
  const tableFilterPaneState = useSelector(getTableFilterState);

  useEffect(() => {
    const filters: ReactTableFilter[] = props.filters ? [...props.filters] : [];
    if (filters.length === 0) {
      filters.push({
        column: "",
        operator: OperatorTypes.OR,
        value: "",
        condition: "",
      });
    }
    updateFilters(filters);
  }, [props.filters]);

  const toggleFilterPane = useCallback(
    (selected: boolean) => {
      if (selected) {
        // filter button select
        dispatch(hidePropertyPane());
        dispatch({
          type: ReduxActionTypes.SHOW_TABLE_FILTER_PANE,
          payload: { widgetId: props.widgetId, force: true },
        });
      } else {
        // filter button de-select
        dispatch({
          type: ReduxActionTypes.HIDE_TABLE_FILTER_PANE,
          payload: { widgetId: props.widgetId },
        });
      }
    },
    [props.widgetId],
  );

  if (props.columns.length === 0) {
    return (
      <TableIconWrapper disabled>
        <IconWrapper color={Colors.CADET_BLUE} height={20} width={20}>
          <FilterIcon />
        </IconWrapper>
      </TableIconWrapper>
    );
  }

  const hasAnyFilters = !!(
    filters.length >= 1 &&
    filters[0].column &&
    filters[0].condition
  );
  const className =
    "t--table-filter-toggle-btn " + generateClassName(props.widgetId);
  const isTableFilterPaneVisible =
    tableFilterPaneState.isVisible &&
    tableFilterPaneState.widgetId === props.widgetId;

  return (
    <>
      <TableAction
        className={className}
        icon={
          hasAnyFilters ? (
            <SelectedFilterWrapper>{filters.length}</SelectedFilterWrapper>
          ) : null
        }
        selectMenu={toggleFilterPane}
        selected={isTableFilterPaneVisible}
        title="Filters"
      >
        <FilterIcon />
      </TableAction>
      <TableFilterPane {...props} />
    </>
  );
}

export default TableFilters;
