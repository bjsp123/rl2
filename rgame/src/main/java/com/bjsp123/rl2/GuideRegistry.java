package com.bjsp123.rl2;

import com.bjsp123.rl2.util.CsvTable;

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
            String title    = CsvTable.str(row, "title",  "");
            String imageKey = CsvTable.str(row, "image",  "");
            String para1    = CsvTable.str(row, "para1",  "");
            String para2    = CsvTable.str(row, "para2",  "");
            String para3    = CsvTable.str(row, "para3",  "");
            if (!title.isEmpty()) {
                PAGES.add(new HelpPage(title, imageKey, para1, para2, para3));
            }
        }
    }

    public static List<HelpPage> pages() {
        return Collections.unmodifiableList(PAGES);
    }
}
