/*
 * Copyright (C) 2008, 2009 Wayne Meissner
 *
 * This file is part of jruby-cext.
 *
 * This code is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details.
 *
 * You should have received a copy of the GNU General Public License
 * version 3 along with this work.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jruby.cext;

import java.lang.Class;
import java.lang.reflect.Field;
import com.kenai.jffi.Library;
import org.jruby.Ruby;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.lang.NoSuchFieldException;
import java.lang.IllegalAccessException;


final class Native {
    private static Native INSTANCE;
    private static Library shim = null; // keep a hard ref to avoid GC

    public synchronized static final Native getInstance(Ruby runtime)
            throws IllegalAccessException, NoSuchFieldException {
        if (INSTANCE == null) {
            INSTANCE = new Native();
            INSTANCE.load(runtime);
        }

        return INSTANCE;
    }


    private void load(Ruby runtime) throws NoSuchFieldException, IllegalAccessException {

        // Force the shim library to load into the global namespace
        if ((shim = Library.openLibrary(System.mapLibraryName("jruby-cext"), Library.NOW | Library.GLOBAL)) == null) {
            throw new UnsatisfiedLinkError("failed to load shim library, error: " + Library.getLastError());
        }

        Class clazz = ClassLoader.class;        
        Field field = clazz.getDeclaredField("sys_paths");        
        boolean accessible = field.isAccessible();
        if (!accessible) field.setAccessible(true);
        Object original = field.get(clazz);
        // Reset it to null so that whenever "System.loadLibrary" is called, 
        // it will be reconstructed with the changed value. Dirty, I know
        //field.set(clazz, null);
        try {
            // Change the value and load the library.
            System.setProperty("java.library.path", 
                    runtime.getInstanceConfig().getJRubyHome() + "/lib/ruby/cext");
            System.out.println("java.library.path: " + System.getProperty("java.library.path"));

            System.loadLibrary("jruby-cext");
            // Register Qfalse, Qtrue, Qnil constants to avoid reverse lookups in native code
            GC.register(runtime.getFalse(), new Handle(runtime, getFalse()));
            GC.register(runtime.getTrue(), new Handle(runtime, getTrue()));
            GC.register(runtime.getNil(), new Handle(runtime, getNil()));

            initNative(runtime);
        } finally {
            //Revert back the changes to the java.library.path
            field.set(clazz, original);
            field.setAccessible(accessible);
        }
    }


    private final native void initNative(Ruby runtime);
    
    public final native long callInit(ThreadContext ctx, long init);
    public final native IRubyObject callMethod(ThreadContext ctx, long fn, IRubyObject recv, int arity, IRubyObject[] args);

    public final native long newHandle(IRubyObject obj);
    public final native void freeHandle(long handle);
    public final native void markHandle(long handle);
    public final native void unmarkHandle(long handle);

    private final native int getNil();
    private final native int getTrue();
    private final native int getFalse();
}
