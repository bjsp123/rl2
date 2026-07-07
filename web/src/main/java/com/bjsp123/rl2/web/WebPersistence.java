package com.bjsp123.rl2.web;

import com.bjsp123.rl2.persistence.Persistence;
import org.teavm.jso.browser.Storage;

/**
 * Browser implementation of the {@link Persistence} key-blob store, backed by
 * {@code window.localStorage}. localStorage is synchronous by nature, so it
 * satisfies the interface's synchronous contract directly - the same way
 * DesktopPersistence does with files. Keys keep their {@code rl2-*} names;
 * localStorage is already per-origin so no extra namespacing is needed.
 */
public final class WebPersistence implements Persistence {

    private final Storage store = Storage.getLocalStorage();

    @Override
    public String load(String key) {
        return store.getItem(key);
    }

    @Override
    public void save(String key, String value) {
        store.setItem(key, value);
    }

    @Override
    public void delete(String key) {
        store.removeItem(key);
    }

    @Override
    public boolean exists(String key) {
        return store.getItem(key) != null;
    }
}
