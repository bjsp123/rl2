package com.bjsp123.rl2.save;

import com.bjsp123.rl2.persistence.Persistence;
import java.util.HashMap;
import java.util.Map;

/** Map-backed {@link Persistence} fake for headless save/load tests. */
public class InMemoryPersistence implements Persistence {

    private final Map<String, String> store = new HashMap<>();

    @Override
    public String load(String key) { return store.get(key); }

    @Override
    public void save(String key, String value) { store.put(key, value); }

    @Override
    public void delete(String key) { store.remove(key); }

    @Override
    public boolean exists(String key) { return store.containsKey(key); }
}
