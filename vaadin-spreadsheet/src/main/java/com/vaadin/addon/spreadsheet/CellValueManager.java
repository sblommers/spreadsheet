package com.vaadin.addon.spreadsheet;

import java.lang.reflect.Field;
import java.text.Format;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.hssf.model.InternalSheet;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFHyperlink;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.formula.FormulaParseException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFSheet;

import com.vaadin.addon.spreadsheet.Spreadsheet.CellValueHandler;
import com.vaadin.addon.spreadsheet.command.CellValueCommand;
import com.vaadin.ui.UI;

public class CellValueManager {

    private static final String numericCellDetectionPattern = "[^A-Za-z]*[0-9]+[^A-Za-z]*";
    private static final String rowShiftRegex = "[$]?[a-zA-Z]+[$]?\\d+";
    private static final Pattern rowShiftPattern = Pattern
            .compile(rowShiftRegex);

    private short hyperlinkStyleIndex = -1;

    protected final Spreadsheet spreadsheet;

    private CellValueHandler customCellValueHandler;

    private DataFormatter formatter;

    private FormulaEvaluator evaluator;

    /** Cell keys that have values sent to client side and are cached there. */
    private final HashSet<String> sentCells = new HashSet<String>();
    /**
     * Formula cell keys that have values sent to client side and are cached
     * there.
     */
    private final HashSet<String> sentFormulaCells = new HashSet<String>();
    /** */
    private final HashSet<String> removedCells = new HashSet<String>();
    /** */
    private final HashSet<String> markedCells = new HashSet<String>();

    public CellValueManager(Spreadsheet spreadsheet) {
        this.spreadsheet = spreadsheet;
        UI current = UI.getCurrent();
        if (current != null) {
            formatter = new DataFormatter(current.getLocale());
        } else {
            formatter = new DataFormatter();
        }
    }

    public CellSelectionManager getCellSelectionManager() {
        return spreadsheet.getCellSelectionManager();
    }

    public void clearEvaluatorCache() {
        evaluator.clearAllCachedResultValues();
    }

    public void clearCachedContent() {
        markedCells.clear();
        sentCells.clear();
        removedCells.clear();
        sentFormulaCells.clear();
        hyperlinkStyleIndex = -1;
    }

    public DataFormatter getDataFormatter() {
        return formatter;
    }

    public void setDataFormatter(DataFormatter dataFormatter) {
        formatter = dataFormatter;
    }

    public void updateFormatter(Locale locale) {
        formatter = new DataFormatter(locale);
    }

    public FormulaEvaluator getFormulaEvaluator() {
        return evaluator;
    }

    public void setFormulaEvaluator(FormulaEvaluator formulaEvaluator) {
        evaluator = formulaEvaluator;
    }

    public void updateEvaluator() {
        evaluator = spreadsheet.getWorkbook().getCreationHelper()
                .createFormulaEvaluator();
    }

    public String getCellValue(Cell cell) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException rte) {
            return "ERROR:" + rte.getMessage();
        }
    }

    /**
     * @return the customCellValueHandler
     */
    public CellValueHandler getCustomCellValueHandler() {
        return customCellValueHandler;
    }

    /**
     * @param customCellValueHandler
     *            the customCellValueHandler to set
     */
    public void setCustomCellValueHandler(
            CellValueHandler customCellValueHandler) {
        this.customCellValueHandler = customCellValueHandler;
    }

    /**
     * Notifies evaluator and marks cell for update on next
     * {@link #updateMarkedCellValues(int, int, int, int)}
     * 
     * @param cell
     */
    protected void cellUpdated(Cell cell) {
        evaluator.notifyUpdateCell(cell);
        markCellForUpdate(cell);
    }

    /**
     * Marks cell for update on next
     * {@link #updateMarkedCellValues(int, int, int, int)}
     * 
     * @param cell
     */
    protected void markCellForUpdate(Cell cell) {
        markedCells.add(SpreadsheetUtil.toKey(cell));
    }

    protected void cellDeleted(Cell cell) {
        evaluator.notifyDeleteCell(cell);
        markCellForRemove(cell);
    }

    protected void markCellForRemove(Cell cell) {
        String cellKey = SpreadsheetUtil.toKey(cell);
        removedCells.add(Integer.toString(cell.getRowIndex() + 1));
        removedCells.add(cellKey);
        clearCellCache(cellKey);
    }

    protected void clearCellCache(String cellKey) {
        if (!sentCells.remove(cellKey)) {
            sentFormulaCells.remove(cellKey);
        }
    }

    /**
     * Updates the cell value and type, causes a recalculation of all the values
     * in the cell.
     * 
     * If there is a {@link CellValueHandler} defined, then it is used.
     * 
     * Cells starting with "=" will be created/changed into FORMULA type.
     * 
     * Cells that are existing and are NUMERIC type will be parsed according to
     * their existing format, or if that fails, as Double.
     * 
     * Cells not containing any letters and containing at least one number will
     * be created/changed into NUMERIC type (formatting is not changed).
     * 
     * Existing Boolean cells will be parsed as Boolean.
     * 
     * For everything else and if any of the above fail, the cell will get the
     * STRING type and the value will just be a string, except empty values will
     * cause the cell type to be BLANK.
     * 
     * @param col
     *            1-based
     * @param row
     *            1-based
     * @param value
     *            the String value, formulas will start with an extra "="
     */
    public void onCellValueChange(int col, int row, String value) {
        Workbook workbook = spreadsheet.getWorkbook();
        // update cell value
        final Sheet activeSheet = workbook.getSheetAt(workbook
                .getActiveSheetIndex());
        Row r = activeSheet.getRow(row - 1);
        if (r == null) {
            r = activeSheet.createRow(row - 1);
        }
        Cell cell = r.getCell(col - 1);

        // capture cell value to history
        CellValueCommand command = new CellValueCommand(spreadsheet);
        command.captureCellValues(new CellReference(row - 1, col - 1));
        spreadsheet.getSpreadsheetHistoryManager().addCommand(command);

        if (getCustomCellValueHandler() == null
                || getCustomCellValueHandler().cellValueUpdated(cell,
                        activeSheet, col - 1, row - 1, value, evaluator,
                        formatter)) {
            try {
                // handle new cell creation
                SpreadsheetStyleFactory styler = spreadsheet
                        .getSpreadsheetStyleFactory();
                if (cell == null) {
                    if (value.startsWith("=")) {
                        cell = r.createCell(col - 1, Cell.CELL_TYPE_FORMULA);
                        cell.setCellFormula(value.substring(1));
                        evaluator.notifySetFormula(cell);
                        if (value.startsWith("=HYPERLINK(")) {
                            // set the cell style to link cell
                            CellStyle hyperlinkCellStyle;
                            if (hyperlinkStyleIndex == -1) {
                                hyperlinkCellStyle = styler
                                        .createHyperlinkCellStyle();
                                hyperlinkStyleIndex = -1;
                            } else {
                                hyperlinkCellStyle = workbook
                                        .getCellStyleAt(hyperlinkStyleIndex);
                            }
                            cell.setCellStyle(hyperlinkCellStyle);
                            styler.cellStyleUpdated(cell, true);
                        }
                    } else {
                        if (value.isEmpty()) {
                            cell = r.createCell(col - 1); // BLANK
                        } else if (value.matches(numericCellDetectionPattern)) {
                            cell = r.createCell(col - 1, Cell.CELL_TYPE_NUMERIC);
                            try {
                                cell.setCellValue(Double.parseDouble(value));
                            } catch (NumberFormatException nfe) {
                                cell.setCellValue(value);
                            }
                        } else {
                            cell = r.createCell(col - 1, Cell.CELL_TYPE_STRING);
                            cell.setCellValue(value);
                        }
                        evaluator.notifyUpdateCell(cell);
                    }
                } else { // modify existing cell, possibly switch type
                    final String key = SpreadsheetUtil.toKey(col, row);
                    final int cellType = cell.getCellType();
                    if (!sentCells.remove(key)) {
                        sentFormulaCells.remove(key);
                    }
                    if (value.startsWith("=")) {
                        evaluator.notifyUpdateCell(cell);
                        cell.setCellType(Cell.CELL_TYPE_FORMULA);
                        cell.setCellFormula(value.substring(1));
                        evaluator.notifySetFormula(cell);
                        if (value.startsWith("=HYPERLINK(")
                                && cell.getCellStyle().getIndex() != hyperlinkStyleIndex) {
                            // set the cell style to link cell
                            CellStyle hyperlinkCellStyle;
                            if (hyperlinkStyleIndex == -1) {
                                hyperlinkCellStyle = styler
                                        .createHyperlinkCellStyle();
                                hyperlinkStyleIndex = -1;
                            } else {
                                hyperlinkCellStyle = workbook
                                        .getCellStyleAt(hyperlinkStyleIndex);
                            }
                            cell.setCellStyle(hyperlinkCellStyle);
                            styler.cellStyleUpdated(cell, true);
                        }
                    } else {
                        if (value.isEmpty()) {
                            cell.setCellType(Cell.CELL_TYPE_BLANK);
                        } else if (cellType == Cell.CELL_TYPE_NUMERIC) {
                            parseValueIntoNumericCell(cell, value);
                        } else if (value.matches(numericCellDetectionPattern)) {
                            if (cellType == Cell.CELL_TYPE_FORMULA) {
                                cell.setCellType(Cell.CELL_TYPE_NUMERIC);
                            }
                            try {
                                cell.setCellValue(Double.parseDouble(value));
                            } catch (NumberFormatException nfe) {
                                cell.setCellValue(value);
                            }
                        } else if (cellType == Cell.CELL_TYPE_BOOLEAN) {
                            cell.setCellValue(Boolean.parseBoolean(value));
                        } else {
                            if (cellType == Cell.CELL_TYPE_FORMULA) {
                                cell.setCellType(Cell.CELL_TYPE_STRING);
                            }
                            cell.setCellValue(value);
                        }
                        evaluator.notifyUpdateCell(cell);
                    }
                }
            } catch (FormulaParseException fpe) {
                try {
                    System.out.println(fpe.getMessage());
                    cell.setCellFormula(value.substring(1).replace(" ", ""));
                } catch (FormulaParseException fpe2) {
                    System.out.println(fpe2.getMessage());
                    cell.setCellValue(value);
                }
            } catch (NumberFormatException nfe) {
                System.out.println(nfe.getMessage());
                cell.setCellValue(value);
            } catch (Exception e) {
                System.out.println(e.getMessage());
                cell.setCellValue(value);
            }
        }

        // update cell values
        updateMarkedCellValues(col, col, row, row);
    }

    /**
     * Deletes the currently selected cells' values. Does not effect styles.
     */
    public void onDeleteSelectedCells() {
        final Sheet activeSheet = spreadsheet.getActiveSheet();
        final CellReference selectedCellReference = getCellSelectionManager()
                .getSelectedCellReference();
        // TODO show error on locked cells instead
        if (selectedCellReference != null) {
            Row row = activeSheet.getRow(selectedCellReference.getRow());
            if (row != null
                    && spreadsheet.isCellLocked(row
                            .getCell(selectedCellReference.getCol()))) {
                return;
            }
        }
        List<CellReference> individualSelectedCells = getCellSelectionManager()
                .getIndividualSelectedCells();
        for (CellReference cr : individualSelectedCells) {
            final Row row = activeSheet.getRow(cr.getRow());
            if (row != null
                    && spreadsheet.isCellLocked(row.getCell(cr.getCol()))) {
                return;
            }
        }
        List<CellRangeAddress> cellRangeAddresses = getCellSelectionManager()
                .getCellRangeAddresses();
        for (CellRangeAddress range : cellRangeAddresses) {
            if (!spreadsheet.isRangeEditable(range)) {
                return;
            }
        }
        CellValueCommand command = new CellValueCommand(spreadsheet);
        if (selectedCellReference != null) {
            command.captureCellValues(selectedCellReference);
            removeCell(selectedCellReference.getCol() + 1,
                    selectedCellReference.getRow() + 1, false);
        }
        for (CellReference cr : individualSelectedCells) {
            command.captureCellValues(cr);
            removeCell(cr.getCol() + 1, cr.getRow() + 1, false);
        }
        for (CellRangeAddress range : cellRangeAddresses) {
            command.captureCellRangeValues(range);
            removeCells(range.getFirstColumn() + 1, range.getLastColumn() + 1,
                    range.getFirstRow() + 1, range.getLastRow() + 1, false);
        }
        // removeCell and removeCells makes sure that cells are removed and
        // cleared from client side cache.
        updateMarkedCellValues(0, 0, 0, 0);
        spreadsheet.getSpreadsheetHistoryManager().addCommand(command);
    }

    protected void parseValueIntoNumericCell(final Cell cell, final String value) {
        // try to parse the string with the existing cell format
        Format oldFormat = formatter.createFormat(cell);
        if (oldFormat != null) {
            try {
                final Object parsedObject = oldFormat.parseObject(value);
                if (parsedObject instanceof Date) {
                    cell.setCellValue((Date) parsedObject);
                } else if (parsedObject instanceof Calendar) {
                    cell.setCellValue((Calendar) parsedObject);
                } else if (parsedObject instanceof Number) {
                    cell.setCellValue(((Number) parsedObject).doubleValue());
                } else {
                    cell.setCellValue(Double.parseDouble(value));
                }
            } catch (ParseException pe) {
                System.out.println("Could not parse String to format, "
                        + oldFormat.getClass() + ", "
                        + cell.getCellStyle().getDataFormatString() + " : "
                        + pe.getMessage());
                try {
                    cell.setCellValue(Double.parseDouble(value));
                } catch (NumberFormatException nfe) {
                    System.out.println("Could not parse String to Double: "
                            + nfe.getMessage());
                    cell.setCellValue(value);
                }
            } catch (NumberFormatException nfe) {
                System.out.println("Could not parse String to Double: "
                        + nfe.getMessage());
                cell.setCellValue(value);
            }
        }
    }

    protected void loadCellData(int firstRow, int lastRow, int firstColumn,
            int lastColumn) {
        // hssf (xls) document contain something on all rows & columns, as xssf
        // (xlsx) documents don't (empty rows and cells just don't exist)
        try {
            Workbook workbook = spreadsheet.getWorkbook();
            final HashMap<String, String> cellDataMap = new HashMap<String, String>();
            final HashMap<String, Double> numericCellDataMap = new HashMap<String, Double>();
            final Sheet activeSheet = workbook.getSheetAt(workbook
                    .getActiveSheetIndex());
            Map<String, String> componentIDtoCellKeysMap = spreadsheet
                    .getState(false).componentIDtoCellKeysMap;
            @SuppressWarnings("unchecked")
            final Collection<String> customComponentCells = (Collection<String>) (componentIDtoCellKeysMap == null ? Collections
                    .emptyList() : componentIDtoCellKeysMap.values());
            for (int r = firstRow - 1; r < lastRow; r++) {
                Row row = activeSheet.getRow(r);
                if (row != null && row.getLastCellNum() != -1
                        && row.getLastCellNum() >= firstColumn) {
                    boolean rowHasContent = false;
                    for (int c = firstColumn - 1; c < lastColumn; c++) {
                        final String key = SpreadsheetUtil.toKey(c + 1, r + 1);
                        if (!customComponentCells.contains(key)
                                && !sentCells.contains(key)
                                && !sentFormulaCells.contains(key)) {
                            Cell cell = row.getCell(c);
                            if (cell != null) {
                                final String contents = getCellValue(cell);
                                if (contents != null && !contents.isEmpty()) {
                                    cellDataMap.put(key, contents);
                                    if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
                                        sentFormulaCells.add(key);
                                    } else {
                                        sentCells.add(key);
                                    }

                                    if (!SpreadsheetUtil.cellContainsDate(cell)
                                            && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
                                        numericCellDataMap.put(key,
                                                cell.getNumericCellValue());
                                    }

                                    rowHasContent = true;
                                }
                            }
                        }
                    }
                    if (rowHasContent) {
                        final String key = Integer.toString(r + 1);
                        cellDataMap.put(key, null);
                    }
                }
            }
            HashMap<Integer, String> cellStyleToCSSSelector = spreadsheet
                    .createCellStyleToCSSSelector();
            if (!cellDataMap.isEmpty() || !cellStyleToCSSSelector.isEmpty()) {
                spreadsheet.getSpreadsheetRpcProxy().addCells(cellDataMap,
                        numericCellDataMap, cellStyleToCSSSelector);
            }

            SpreadsheetFactory.logMemoryUsage();
        } catch (NullPointerException npe) {
            npe.printStackTrace();
        }
    }

    /**
     * Method for updating the spreadsheet client side visible cells and cached
     * data correctly.
     * 
     * Iterates over the given range and makes sure the client side is updated
     * correctly for the range. Handles clearing of missing rows/ from the
     * cache. This iteration can be skipped by giving r1 as 0.
     * 
     * Iterates over the whole spreadsheet (existing rows&columns) and updates
     * client side cache for all sent formula cells, and cells that have been
     * marked for updating.
     * 
     * Parameters 1-based.
     */
    protected void updateMarkedCellValues(int c1, int c2, int r1, int r2) {
        // on both iterators it is unnecessary to worry about having custom
        // components in the cell because the client side handles it -> it will
        // not replace a custom component with a cell value

        final HashMap<String, String> updatedCellData = new HashMap<String, String>();
        final HashMap<String, Double> numericCellData = new HashMap<String, Double>();
        Workbook workbook = spreadsheet.getWorkbook();
        Sheet sheet = spreadsheet.getActiveSheet();
        if (r1 != 0) {
            for (int r = r1; r <= r2; r++) {
                Row row = sheet.getRow(r - 1);
                if (row != null) {
                    boolean rowHasContent = false;
                    boolean hasRemovedContent = false;
                    for (int c = c1; c <= c2; c++) {
                        Cell cell = row.getCell(c - 1);
                        final String key = SpreadsheetUtil.toKey(c, r);
                        if (cell != null && !removedCells.contains(key)) {
                            final String value = getCellValue(cell);
                            if (cell.getCellType() != Cell.CELL_TYPE_FORMULA
                                    && ((value != null && !value.isEmpty()) || sentCells
                                            .contains(key))) {
                                sentCells.add(key);
                                updatedCellData.put(key, value);
                                rowHasContent = true;

                                if (!SpreadsheetUtil.cellContainsDate(cell)
                                        && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
                                    numericCellData.put(key,
                                            cell.getNumericCellValue());
                                }
                            }
                        } else if (sentCells.contains(key)) {
                            // cell doesn't exist, if it was removed the cell
                            // key
                            // should be in removedCells
                            sentCells.remove(key);
                            removedCells.add(key);
                            hasRemovedContent = true;
                        } else if (sentFormulaCells.contains(key)) {
                            sentFormulaCells.remove(key);
                            removedCells.add(key);
                            hasRemovedContent = true;
                        }
                    }
                    if (rowHasContent) {
                        updatedCellData.put(Integer.toString(r), null);
                    }
                    if (hasRemovedContent) {
                        removedCells.add(Integer.toString(r));
                    }
                } else {
                    // row doesn't exist, need to check that cached values are
                    // removed for it properly
                    updateDeletedRowsInClientCache(r, r);
                }
            }
        }
        // update all cached formula cell values on client side, because they
        // might have changed. also make sure all marked cells are updated
        Iterator<Row> rows = workbook
                .getSheetAt(workbook.getActiveSheetIndex()).rowIterator();
        while (rows.hasNext()) {
            final Row r = rows.next();
            final Iterator<Cell> cells = r.cellIterator();
            boolean rowHasContent = false;
            while (cells.hasNext()) {
                final Cell cell = cells.next();
                int rowIndex = cell.getRowIndex();
                int columnIndex = cell.getColumnIndex();
                final String key = SpreadsheetUtil.toKey(columnIndex + 1,
                        rowIndex + 1);
                final String value = getCellValue(cell);
                // update formula cells
                if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
                    if (value != null && !value.isEmpty()) {
                        if (sentFormulaCells.contains(key)
                                || markedCells.contains(key)
                                || (rowIndex + 1 >= r1 && rowIndex + 1 <= r2
                                        && columnIndex + 1 >= c1 && columnIndex <= c2)) {
                            sentFormulaCells.add(key);
                            updatedCellData.put(key, value);
                            rowHasContent = true;
                        }
                    } else if (sentFormulaCells.contains(key)) {
                        // in case the formula cell value has changed to null or
                        // empty; this case is probably quite rare, formula cell
                        // pointing to a cell that was removed or had its value
                        // cleared ???
                        sentFormulaCells.add(key);
                        updatedCellData.put(key, "");
                        rowHasContent = true;
                    }
                } else if (markedCells.contains(key)) {
                    sentCells.add(key);
                    updatedCellData.put(key, value);
                    rowHasContent = true;

                    if (!SpreadsheetUtil.cellContainsDate(cell)
                            && cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
                        numericCellData.put(key, cell.getNumericCellValue());
                    }

                }
            }
            if (rowHasContent) {
                final String key = Integer.toString(r.getRowNum() + 1);
                updatedCellData.put(key, null);
            }

        }
        if (removedCells.isEmpty()) {
            spreadsheet.getSpreadsheetRpcProxy()
                    .addCells(updatedCellData, numericCellData,
                            spreadsheet.createCellStyleToCSSSelector());

        } else {
            // FIXME investigate why HashSet<String> is not
            // serializing/deserializing
            spreadsheet.getSpreadsheetRpcProxy().addUpdatedCells(
                    updatedCellData, numericCellData,
                    new ArrayList<String>(removedCells),
                    spreadsheet.createCellStyleToCSSSelector());
        }
        markedCells.clear();
        removedCells.clear();
    }

    /**
     * Makes sure the next {@link #updateMarkedCellValues(int, int, int, int)}
     * call will clear all removed rows from client cache.
     * 
     * @param startRow
     *            1-based
     * @param endRow
     *            1-based
     */
    protected void updateDeletedRowsInClientCache(int startRow, int endRow) {
        for (int i = startRow; i <= endRow; i++) {
            String rowKey = "row" + i;
            boolean rowIsRemoved = false;
            for (Iterator<String> iterator = sentCells.iterator(); iterator
                    .hasNext();) {
                String key = iterator.next();
                if (key.endsWith(rowKey)) {
                    iterator.remove();
                    removedCells.add(key);
                    rowIsRemoved = true;
                }
            }
            for (Iterator<String> iterator = sentFormulaCells.iterator(); iterator
                    .hasNext();) {
                String key = iterator.next();
                if (key.endsWith(rowKey)) {
                    iterator.remove();
                    removedCells.add(key);
                    rowIsRemoved = true;
                }
            }
            if (rowIsRemoved) {
                removedCells.add(Integer.toString(i));
            }
        }
    }

    /**
     * Indexes 1-based
     * 
     * @param col1
     * @param col2
     * @param row1
     * @param row2
     * @param clearRemovedCellStyle
     */
    protected void removeCells(int col1, int col2, int row1, int row2,
            boolean clearRemovedCellStyle) {
        final Workbook workbook = spreadsheet.getWorkbook();
        final Sheet activeSheet = workbook.getSheetAt(workbook
                .getActiveSheetIndex());
        for (int i = row1 - 1; i < row2; i++) {
            Row row = activeSheet.getRow(i);
            if (row != null) {
                removedCells.add(Integer.toString(i + 1));
                for (int j = col1 - 1; j < col2; j++) {
                    Cell cell = row.getCell(j);
                    if (cell != null) {
                        final String key = SpreadsheetUtil.toKey(j + 1, i + 1);
                        if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
                            sentFormulaCells.remove(key);
                        } else {
                            sentCells.remove(key);
                        }
                        if (cell.getHyperlink() != null) {
                            removeHyperlink(cell, activeSheet);
                        }
                        if (clearRemovedCellStyle) {
                            // update style to 0
                            cell.setCellStyle(null);
                            spreadsheet.getSpreadsheetStyleFactory()
                                    .cellStyleUpdated(cell, true);
                        }
                        // need to make protection etc. settings for the cell
                        // won't get effected. deleting the cell would make it
                        // locked
                        removedCells.add(key);
                        cell.setCellValue((String) null);
                        evaluator.notifyUpdateCell(cell);
                    }
                }
            }
        }
    }

    /**
     * 
     * @param colIndex
     *            1-based
     * @param rowIndex
     *            1-based
     * @param clearRemovedCellStyle
     */
    protected void removeCell(int colIndex, int rowIndex,
            boolean clearRemovedCellStyle) {
        final Workbook workbook = spreadsheet.getWorkbook();
        final Sheet activeSheet = workbook.getSheetAt(workbook
                .getActiveSheetIndex());
        final Row row = activeSheet.getRow(rowIndex - 1);
        if (row != null) {
            final Cell cell = row.getCell(colIndex - 1);
            if (cell != null) {
                removedCells.add(Integer.toString(rowIndex));
                final String key = SpreadsheetUtil.toKey(colIndex, rowIndex);
                if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
                    sentFormulaCells.remove(key);
                } else {
                    sentCells.remove(key);
                }
                // POI (3.9) doesn't have a method for removing a hyperlink !!!
                if (cell.getHyperlink() != null) {
                    removeHyperlink(cell, activeSheet);
                }
                if (clearRemovedCellStyle) {
                    // update style to 0
                    cell.setCellStyle(null);
                    spreadsheet.getSpreadsheetStyleFactory().cellStyleUpdated(
                            cell, true);
                }
                cell.setCellValue((String) null);
                evaluator.notifyUpdateCell(cell);

                removedCells.add(key);
            }
        }
    }

    protected void removeHyperlink(Cell cell, Sheet sheet) {
        try {
            if (sheet instanceof XSSFSheet) {
                Field f;
                f = XSSFSheet.class.getDeclaredField("hyperlinks");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<XSSFHyperlink> hyperlinks = (List<XSSFHyperlink>) f
                        .get(sheet);
                hyperlinks.remove(cell.getHyperlink());
                f.setAccessible(false);
            } else if (sheet instanceof HSSFSheet && cell instanceof HSSFCell) {
                HSSFHyperlink link = (HSSFHyperlink) cell.getHyperlink();
                Field sheetField = HSSFSheet.class.getDeclaredField("_sheet");
                sheetField.setAccessible(true);
                InternalSheet internalsheet = (InternalSheet) sheetField
                        .get(sheet);
                List<RecordBase> records = internalsheet.getRecords();
                Field recordField = HSSFHyperlink.class
                        .getDeclaredField("record");
                recordField.setAccessible(true);
                records.remove(recordField.get(link));
                sheetField.setAccessible(false);
                recordField.setAccessible(false);
            }
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    protected void shiftCellValue(Cell shiftedCell, Cell newCell,
            boolean removeShifted) {
        // clear the new cell first because it might have errors which prevent
        // it from being set to a new type
        if (newCell.getCellType() != Cell.CELL_TYPE_BLANK
                || shiftedCell.getCellType() == Cell.CELL_TYPE_BLANK) {
            newCell.setCellType(Cell.CELL_TYPE_BLANK);
        }
        newCell.setCellType(shiftedCell.getCellType());
        newCell.setCellStyle(shiftedCell.getCellStyle());
        spreadsheet.getSpreadsheetStyleFactory()
                .cellStyleUpdated(newCell, true);
        if (shiftedCell.getCellType() == Cell.CELL_TYPE_FORMULA) {
            try {
                if (shiftedCell.getColumnIndex() != newCell.getColumnIndex()) {
                    // shift column indexes
                    int collDiff = newCell.getColumnIndex()
                            - shiftedCell.getColumnIndex();
                    Matcher matcher = rowShiftPattern.matcher(shiftedCell
                            .getCellFormula());
                    String originalFormula = shiftedCell.getCellFormula();
                    String newFormula = originalFormula;
                    while (matcher.find()) {
                        String s = matcher.group();
                        if (!s.startsWith("$")) {
                            int replaceIndex = newFormula.indexOf(s);
                            while (replaceIndex > 0
                                    && newFormula.charAt(replaceIndex - 1) == '$') {
                                replaceIndex = newFormula.indexOf(s,
                                        replaceIndex + 1);
                            }
                            if (replaceIndex > -1) {
                                String oldIndexString = s.replaceAll(
                                        "[$]{0,1}\\d+", "");

                                int columnIndex = SpreadsheetUtil
                                        .getColHeaderIndex(oldIndexString);
                                columnIndex += collDiff;
                                String replacement = s.replace(oldIndexString,
                                        SpreadsheetUtil
                                                .getColHeader(columnIndex));
                                newFormula = newFormula.substring(0,
                                        replaceIndex)
                                        + replacement
                                        + newFormula.substring(replaceIndex
                                                + s.length());
                            }
                        }
                    }
                    newCell.setCellFormula(newFormula);
                } else { // shift row indexes
                    int rowDiff = newCell.getRowIndex()
                            - shiftedCell.getRowIndex();
                    Matcher matcher = rowShiftPattern.matcher(shiftedCell
                            .getCellFormula());
                    String originalFormula = shiftedCell.getCellFormula();
                    String newFormula = originalFormula;
                    while (matcher.find()) {
                        String s = matcher.group();
                        String rowString = s.replaceAll(
                                "([$][a-zA-Z]+)|([a-zA-Z]+)", "");
                        if (!rowString.startsWith("$")) {
                            int row = Integer.parseInt(rowString);
                            row += rowDiff;
                            String replacement = s.replace(rowString,
                                    Integer.toString(row));
                            // impossible to replace a row with $ before it
                            // because
                            // of the column address
                            newFormula = newFormula.replace(s, replacement);
                        }
                    }
                    newCell.setCellFormula(newFormula);
                }
            } catch (Exception e) {
                e.printStackTrace();
                // TODO visialize shifting error
                newCell.setCellFormula(shiftedCell.getCellFormula());
            }
            evaluator.notifySetFormula(newCell);
        } else {
            switch (shiftedCell.getCellType()) {
            case Cell.CELL_TYPE_BOOLEAN:
                newCell.setCellValue(shiftedCell.getBooleanCellValue());
                break;
            case Cell.CELL_TYPE_ERROR:
                newCell.setCellValue(shiftedCell.getErrorCellValue());
                break;
            case Cell.CELL_TYPE_NUMERIC:
                newCell.setCellValue(shiftedCell.getNumericCellValue());
                break;
            case Cell.CELL_TYPE_STRING:
                newCell.setCellValue(shiftedCell.getStringCellValue());
                break;
            case Cell.CELL_TYPE_BLANK:
                // cell is cleared when type is set
            default:
                break;
            }
            evaluator.notifyUpdateCell(newCell);
        }
        if (removeShifted) {
            final String key = SpreadsheetUtil.toKey(
                    shiftedCell.getColumnIndex() + 1,
                    shiftedCell.getRowIndex() + 1);
            removedCells.add(Integer.toString(shiftedCell.getRowIndex() + 1));
            shiftedCell.setCellValue((String) null);
            cellDeleted(shiftedCell);
        }
    }
}