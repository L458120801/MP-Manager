package io.github.abdurazaaqmohammed.adapters;

public interface MultiSelectableAdapter {
    boolean supportsMultiSelect();
    boolean isMultiSelectMode();
    void handleSwipe(int position);
    void handleMultiSelect(int position);
    void selectAll();
    void invertSelection();
    void selectSameType();
    void clearSelection();
    void exitMultiSelectMode();
}
