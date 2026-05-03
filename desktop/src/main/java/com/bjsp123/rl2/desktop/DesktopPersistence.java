package com.bjsp123.rl2.desktop;

import com.bjsp123.rl2.persistence.Persistence;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Stores blobs as files under {@code ~/.rl2/}. */
public class DesktopPersistence implements Persistence {

    private final File dir;

    public DesktopPersistence() {
        this.dir = new File(System.getProperty("user.home"), ".rl2");
        if (!dir.exists()) dir.mkdirs();
    }

    @Override
    public String load(String key) {
        File f = new File(dir, key);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void save(String key, String value) {
        File f = new File(dir, key);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // best-effort
        }
    }

    @Override
    public void delete(String key) {
        File f = new File(dir, key);
        if (f.exists()) f.delete();
    }

    @Override
    public boolean exists(String key) {
        return new File(dir, key).exists();
    }
}
