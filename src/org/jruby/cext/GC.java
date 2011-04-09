/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2009 Wayne Meissner
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.cext;

import java.lang.ref.Reference;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.jruby.Ruby;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.threading.DaemonThreadFactory;
import org.jruby.util.SoftReferenceReaper;

/**
 * The cext {@link GC} keeps track of native handles and associates them with their corresponding Java objects
 * to avoid garbage-collection while either is in use. It will remove unused references when a thread exits native code
 * or the VM runs out of memory.
 */
public class GC {

    private static final Map<Object, Handle> nativeHandles = new IdentityHashMap<Object, Handle>();
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
    private static volatile Reference<Object> reaper = null;
    private static Runnable gcTask;
    private static volatile Future<?> gcFuture;

    public static final void trigger() {
        if (gcFuture == null || gcFuture.isDone()) {
            gcFuture = executor.submit(gcTask);
        }
    }

    static void init(final Native n) {
        gcTask = new Runnable() {
            public void run() {
                GIL.acquire();
                try {
                    n.gc();
                    Object obj;
                    while ((obj = n.pollGC()) != null) {
                        nativeHandles.remove(obj);
                    }
                } finally {
                    GIL.releaseNoCleanup();
                }
            }
        };
    }

    static final Handle lookup(IRubyObject obj) {
        return nativeHandles.get(obj);
    }

    public static final void addIfMissing(Ruby runtime, IRubyObject obj, long nativeHandle) {
        if (nativeHandles.get(obj) == null) {
            nativeHandles.put(obj, Handle.newHandle(runtime, obj, nativeHandle));
        }
    }

    /**
     * Called from Handle.valueOf
     * @param obj
     */
    static final void register(IRubyObject obj, Handle h) {
        nativeHandles.put(obj, h);
    }

    static final void cleanup() {
        //
        // Trigger a cleanup when the VM runs out of memory and starts clearing
        // soft references.
        //
        if (reaper == null) {
            reaper = new SoftReferenceReaper<Object>(new Object()) {
                public void run() {
                    reaper = null;
                    GC.trigger();
                }
            };
        }
    }
}
