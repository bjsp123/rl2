package com.bjsp123.rl2.persistence;

/**
 * Platform-agnostic key→string blob storage. All platform-specific file/preferences code lives in the
 * implementations: {@code AndroidPersistence} (android module) and {@code DesktopPersistence} (desktop module).
 */
public interface Persistence {

    /** Returns the stored value for {@code key}, or {@code null} if no value is stored. */
    String load(String key);

    /** Writes {@code value} under {@code key}, replacing any prior value. Synchronous. */
    void save(String key, String value);

    /** Removes any value stored under {@code key}. No-op if absent. */
    void delete(String key);

    /** True iff a value is currently stored under {@code key}. */
    boolean exists(String key);
}
