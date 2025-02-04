/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package dev.su5ed.sinytra.connector.service;

import cpw.mods.modlauncher.api.TypesafeMap;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class MixinBlackboard implements IGlobalPropertyService {
    private final Map<String, IPropertyKey> keys = new HashMap<>();
    private final TypesafeMap blackboard;

    public MixinBlackboard() {
        this.blackboard = new TypesafeMap();
    }

    public IPropertyKey resolveKey(String name) {
        return this.keys.computeIfAbsent(name, key -> new Key<>(this.blackboard, key, Object.class));
    }

    public <T> T getProperty(IPropertyKey key) {
        return (T) this.getProperty(key, null);
    }

    public void setProperty(IPropertyKey key, Object value) {
        this.blackboard.computeIfAbsent(((Key) key).key, k -> value);
    }

    public String getPropertyString(IPropertyKey key, String defaultValue) {
        return this.getProperty(key, defaultValue);
    }

    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        return (T) this.blackboard.get(((Key) key).key).orElse(defaultValue);
    }

    static class Key<V> implements IPropertyKey {
        final TypesafeMap.Key<V> key;

        public Key(TypesafeMap owner, String name, Class<V> clazz) {
            this.key = cpw.mods.modlauncher.api.TypesafeMap.Key.getOrCreate(owner, name, clazz);
        }
    }
}
