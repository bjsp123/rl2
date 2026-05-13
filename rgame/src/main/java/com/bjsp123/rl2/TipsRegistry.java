package com.bjsp123.rl2;

import com.bjsp123.rl2.util.CsvTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class TipsRegistry {

    private static final List<String> TIPS = new ArrayList<>();

    public static void load(String csv) {
        TIPS.clear();
        CsvTable table = CsvTable.parse(csv);
        for (Map<String, String> row : table.rows) {
            String desc = CsvTable.str(row, "description", "");
            if (!desc.isEmpty()) TIPS.add(desc);
        }
    }

    public static List<String> tips() {
        return Collections.unmodifiableList(TIPS);
    }
}
