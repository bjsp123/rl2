package com.bjsp123.rl2.android;

import android.content.Context;
import com.bjsp123.rl2.persistence.Persistence;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Stores blobs as files under the app's private files directory. */
public class AndroidPersistence implements Persistence {

    private final File dir;

    public AndroidPersistence(Context ctx) {
        this.dir = ctx.getFilesDir();
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
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void save(String key, String value) {
        File f = new File(dir, key);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(value.getBytes("UTF-8"));
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
