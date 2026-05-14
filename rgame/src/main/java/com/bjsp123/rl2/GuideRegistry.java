package com.bjsp123.rl2;

import com.bjsp123.rl2.util.CsvTable;
import com.bjsp123.rl2.logic.TextCatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class GuideRegistry {

    public static final class HelpPage {
        public final String title;
        public final String imageKey;
        public final String para1;
        public final String para2;
        public final String para3;

        public HelpPage(String title, String imageKey,
                        String para1, String para2, String para3) {
            this.title    = title;
            this.imageKey = imageKey;
            this.para1    = para1;
            this.para2    = para2;
            this.para3    = para3;
        }
    }

    private static final List<HelpPage> PAGES = new ArrayList<>();

    public static void load(String csv) {
        PAGES.clear();
        CsvTable table = CsvTable.parse(csv);
        for (Map<String, String> row : table.rows) {
            String key      = CsvTable.str(row, "key", "");
            String title    = key.isEmpty()
                    ? CsvTable.str(row, "title",  "")
                    : TextCatalog.getOrDefault(key + ".title", "");
            String imageKey = CsvTable.str(row, "image",  "");
            String para1    = key.isEmpty() ? CsvTable.str(row, "para1",  "") : TextCatalog.getOrDefault(key + ".para1", "");
            String para2    = key.isEmpty() ? CsvTable.str(row, "para2",  "") : TextCatalog.getOrDefault(key + ".para2", "");
            String para3    = key.isEmpty() ? CsvTable.str(row, "para3",  "") : TextCatalog.getOrDefault(key + ".para3", "");
            if (!title.isEmpty()) {
                PAGES.add(new HelpPage(title, imageKey, para1, para2, para3));
            }
        }
    }

    public static List<HelpPage> pages() {
        return Collections.unmodifiableList(PAGES);
    }
}
