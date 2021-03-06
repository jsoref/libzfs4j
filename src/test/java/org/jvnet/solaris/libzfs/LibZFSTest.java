/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * You can obtain a copy of the license at usr/src/OPENSOLARIS.LICENSE
 * or http://www.opensolaris.org/os/licensing.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at usr/src/OPENSOLARIS.LICENSE.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */
package org.jvnet.solaris.libzfs;

import java.io.File;
import java.util.EnumSet;
import java.util.Map;

import junit.framework.TestCase;

import java.util.logging.*;

import org.jvnet.solaris.libzfs.ACLBuilder;
import org.jvnet.solaris.libzfs.LibZFS;
import org.jvnet.solaris.libzfs.ZFSFileSystem;
import org.jvnet.solaris.libzfs.ZFSObject;
import org.jvnet.solaris.libzfs.ZFSPermission;
import org.jvnet.solaris.libzfs.ZFSPool;
import org.jvnet.solaris.libzfs.ZFSType;
import org.jvnet.solaris.libzfs.jna.zfs_prop_t;
import org.jvnet.solaris.libzfs.jna.zpool_prop_t;

/**
 * Unit test for simple ZFS-aware App.
 *
 * @author Kohsuke Kawaguchi
 * @author Jim Klimov
 */
public class LibZFSTest extends TestCase {

    private static final String ZFS_TEST_POOL_OVERRIDE_PROPERTY = "libzfs.test.pool";

    private static final String ZFS_TEST_POOL_BASENAME_DEFAULT = "rpool/kohsuke/";

    private String ZFS_TEST_POOL_BASENAME;

    private static final String ZFS_TEST_LOGLEVEL_OVERRIDE_PROPERTY = "libzfs.test.loglevel";

    private static final String ZFS_TEST_LOGLEVEL_DEFAULT = "FINE";

    private String ZFS_TEST_LOGLEVEL;

    private static final String ZFS_TEST_FUNCNAME_OVERRIDE_PROPERTY = "libzfs.test.funcname";

    private static final String ZFS_TEST_FUNCNAME_DEFAULT = "";

    private String ZFS_TEST_FUNCNAME;

    private static final String ZFS_TEST_TIMESTAMP = String.valueOf(System.currentTimeMillis());

    private LibZFS zfs = null;

    private Logger LOGGER = null;

    /**
     * The dataset name that can be created in a test.
     * This will be automatically destroyed at the end.
     */
    private String dataSet;

    public void setUp() throws Exception {
        /* Set up logging so the LibZFS FINE log messages can be seen */
        ZFS_TEST_LOGLEVEL = System
                .getProperty(ZFS_TEST_LOGLEVEL_OVERRIDE_PROPERTY,
                        ZFS_TEST_LOGLEVEL_DEFAULT);

        if (LOGGER == null) {
            LOGGER = Logger.getLogger(LibZFS.class.getName());
            //System.out.println("Initial logging level is: " + LOGGER.getLevel());

            // LOG this level to the log */
            Level level;
            try {
                level = Level.parse(ZFS_TEST_LOGLEVEL);
            } catch (Exception e) {
                level = Level.FINE;
            }
            LOGGER.setLevel(level);

            ConsoleHandler handler = new ConsoleHandler();
            /* PUBLISH this level */
            handler.setLevel(level);
            LOGGER.addHandler(handler);

            //System.out.println("Logging level is: " + LOGGER.getLevel());
        }

        super.setUp();

        if (zfs == null) {
            try {
                //System.out.println("Setting up a LibZFS instance for this test run...");
                zfs = new LibZFS();
            } catch (Exception e) {
                System.out.println("Aborted " + getName() + " because: " + e.toString());
                throw new Exception("Aborted " + getName() + " because: " + e.toString());
            }
        }

        /* allows to specify just ZFS function(s) to test, geared for those with
         * variable ABI; this would use the specified dataset directly */
        ZFS_TEST_FUNCNAME = System
                .getProperty(ZFS_TEST_FUNCNAME_OVERRIDE_PROPERTY,
                        ZFS_TEST_FUNCNAME_DEFAULT);

        /* allows override of zfs pool used in testing */
        ZFS_TEST_POOL_BASENAME = System
                .getProperty(ZFS_TEST_POOL_OVERRIDE_PROPERTY,
                        ZFS_TEST_POOL_BASENAME_DEFAULT)
                .replaceAll("/+$", "");

        if (ZFS_TEST_FUNCNAME.isEmpty()) {
                dataSet = ZFS_TEST_POOL_BASENAME + "/" + getName();
                assertFalse("Prerequisite Failed, DataSet already exists [" + dataSet+ "] ", zfs.exists(dataSet));
        } else {
                dataSet = ZFS_TEST_POOL_BASENAME;
                System.out.println("Will test just the following function(s): " + ZFS_TEST_FUNCNAME
                        + "\tin dataset: '" + dataSet + "' (no '" + getName() + "' attached)");
        }
    }

    public void tearDown() throws Exception {
        super.tearDown();

        if (!ZFS_TEST_FUNCNAME.isEmpty()) {
            System.out.println("Quickly ending test " + getName());
            zfs.dispose();
            return;
        }

        if (dataSet != null) {
            System.out.println("TearDown test dataset [" + dataSet + "]");

            if (zfs.exists(dataSet)) {
                final ZFSFileSystem fs = zfs.open(dataSet,ZFSFileSystem.class);
                fs.unshare();
                fs.unmount();
                fs.destroy(true);
            }
        }
        zfs.dispose();
    }

    public void testCouldStart() {
        assertFalse("Native ZFS library could not be loaded", zfs == null);
        System.out.println("LibZFSTest loaded OK");
    }

    public void testApp() {
        /* TODO: Real func name - if more than zfs_iter_snapshots
         * ABI variations are covered later */
        if (!ZFS_TEST_FUNCNAME.isEmpty() && !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_iter_snapshots" + "\\b.*") )
            return;

        System.out.println("Iterating roots");
        Boolean seen_snaps = false;
        for (ZFSFileSystem pool : zfs.roots()) {
            System.out.println(pool.getName());
            for (ZFSObject child : pool.descendants()) {
                System.out.println("- " + child.getName());
                if (!seen_snaps && child.getName().contains("@")) {
                    seen_snaps = true;
                }
            }
        }

        if (!ZFS_TEST_FUNCNAME.isEmpty() && !seen_snaps) {
            System.out.println("WARNING: we tested to iterate snapshots, but none were found under any pool!");
        }
    }

    /* Note: here and below we assume, validly for Solarish systems
     * (global zones at least), that an /rpool exists and is mountable */
    public void testGetFilesystemTree() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        // List<ZFSPool> pools = zfs.roots();
        // if ( pools.size() > 0 ) {
        // ZFSObject filesystem = pools.get(0);
        ZFSObject filesystem = zfs.open("rpool");
        if (filesystem != null) {
            System.out.println("single tree: " + filesystem.getName());
            for (ZFSObject child : filesystem.children()) {
                if (child.getName().contains("@")) {
                    System.out.println("snapshot  :" + child.getName());
                } else {
                    System.out.println("child     :" + child.getName());
                }
            }
        } else {
            System.out.println("no zfs pools were found");
        }
    }

    public void testRpoolMount() throws Exception {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        assertNotNull(zfs.getFileSystemByMountPoint(new File("/rpool")));
    }

    public void testCreate() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty() && !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_create" + "\\b.*") )
            return;

        ZFSObject fs = zfs.create(dataSet, ZFSFileSystem.class);

        assertNotNull("ZFSObject was null for DataSet [" + dataSet + "]",
                fs);
        assertEquals("ZFSObject doesn't match name specified at create",
                dataSet, fs.getName());
        assertTrue("ZFS exists doesn't report ZFS's creation", zfs
                .exists(dataSet));
    }

    public void testDestroy() {
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        zfs.create(dataSet, ZFSFileSystem.class);

        assertTrue("Prerequisite Failed, Test DataSet [" + dataSet
                + "] didn't create", zfs.exists(dataSet));

        ZFSObject fs = zfs.open(dataSet);

        assertNotNull("ZFSObject was null for DataSet [" + dataSet + "]",
                fs);
        assertEquals("ZFSObject doesn't match name specified at open",
                dataSet, fs.getName());
        assertTrue("ZFS exists doesn't report ZFS", zfs.exists(dataSet));

        fs.destroy();

        assertFalse("ZFS exists doesn't report ZFS as destroyed", zfs
                .exists(dataSet));
    }

    public void testfunc__zfs_destroy() {
        if (ZFS_TEST_FUNCNAME.isEmpty())
            return;

        if ( !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_destroy" + "\\b.*") )
            return;

        System.out.println("testfunc__zfs_destroy() : trying to do it...");

        ZFSObject fs = null;
        try {
            zfs.create(dataSet + "/dummy", ZFSFileSystem.class);
        } catch (Exception e) { System.out.println("testfunc__zfs_destroy() : exception: " + e.toString()); }
        try {
            fs = zfs.open(dataSet + "/dummy");
        } catch (Exception e) { System.out.println("testfunc__zfs_destroy() : exception: " + e.toString()); }

        try {
            fs.destroy();
            System.out.println("testfunc__zfs_destroy() : passed the routine");
        } catch (Exception e) { System.out.println("testfunc__zfs_destroy() : exception: " + e.toString()); }
    }

    public void testSnapshot() {
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        ZFSObject fs = zfs.create(dataSet, ZFSFileSystem.class);

        String snap = "libzfstest_" + ZFS_TEST_TIMESTAMP;
        ZFSSnapshot o = fs.createSnapshot(snap);

        assertNotNull("ZFSObject was null for DataSetSnap [" + dataSet + "@" + snap + "]",
                o);
        assertEquals("ZFSObject doesn't match name specified at create",
                dataSet+ "@" + snap, o.getName());
        assertTrue("ZFS exists doesn't report ZFS's creation", zfs
                .exists(dataSet + "@" + snap));

        boolean found = false;
        for (ZFSObject snapds : fs.snapshots()) {
            String name = snapds.getName();
            if (name.equals(snap)) {
                found = true;
                System.out.println("snapshot(*) : " + name);
            } else {
                System.out.println("snapshot    : " + name);
            }
        }

        fs.destroySnapshot(snap);
        /* Should not throw exceptions nor segfault */
    }

    public void testfunc__zfs_snapshot() {
        /* Note: This routine is ONLY for testing the linkability of the function's ABI */
        if (ZFS_TEST_FUNCNAME.isEmpty())
            return;

        /* TODO: Real func name */
        if ( !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_snapshot" + "\\b.*") )
            return;

        System.out.println("testfunc__zfs_snapshot() : trying to do it...");

        ZFSObject fs = null;
        String snap = "libzfstest_" + ZFS_TEST_TIMESTAMP;
        try {
            fs = zfs.open(dataSet);
        } catch (Exception e) { System.out.println("testfunc__zfs_snapshot() : exception: " + e.toString()); }
        try {
            ZFSSnapshot o = fs.createSnapshot(snap);
            System.out.println("testfunc__zfs_snapshot() : passed the routine");
        } catch (Exception e) { System.out.println("testfunc__zfs_snapshot() : exception: " + e.toString()); }
    }

    public void testfunc__zfs_iter_snapshots() {
        /* Note: This routine is ONLY for testing the linkability of the function's ABI */
        if (ZFS_TEST_FUNCNAME.isEmpty())
            return;

        if ( !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_iter_snapshots" + "\\b.*") )
            return;

        System.out.println("testfunc__zfs_iter_snapshots() : trying to do it...");

        ZFSObject fs = null;
        try {
            fs = zfs.open(dataSet);
        } catch (Exception e) { System.out.println("testfunc__zfs_iter_snapshots() : exception: " + e.toString()); }
        try {
            for (ZFSObject snapds : fs.snapshots()) {
                String name = snapds.getName();
            }
            System.out.println("testfunc__zfs_iter_snapshots() : passed the routine");
        } catch (Exception e) { System.out.println("testfunc__zfs_iter_snapshots() : exception: " + e.toString()); }
    }

    public void testfunc__zfs_destroy_snaps() {
        /* Note: This routine is ONLY for testing the linkability of the function's ABI */
        if (ZFS_TEST_FUNCNAME.isEmpty())
            return;

        if ( !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_destroy_snaps" + "\\b.*") )
            return;

        System.out.println("testfunc__zfs_destroy_snaps() : trying to do it...");

        ZFSObject fs = null;
        String snap = "libzfstest_" + ZFS_TEST_TIMESTAMP;
        try {
            fs = zfs.open(dataSet);
        } catch (Exception e) { System.out.println("testfunc__zfs_destroy_snaps() : exception: " + e.toString()); }
        try {
            fs.destroySnapshot(snap);
            System.out.println("testfunc__zfs_destroy_snaps() : passed the routine");
        } catch (Exception e) { System.out.println("testfunc__zfs_destroy_snaps() : exception: " + e.toString()); }
        /* Should not segfault */
    }

    public void testUserProperty() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        ZFSFileSystem o = zfs.create(dataSet,ZFSFileSystem.class);

        String property = "my:test";
        String time = String.valueOf(System.currentTimeMillis());
        o.setProperty(property, time);

        String v = o.getUserProperty(property);
        System.out.println("Property " + property + " is "+ v);
        assertEquals(v,time);
    }

    public void testGetZfsProperties() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        for (ZFSFileSystem pool : zfs.roots()) {
            System.out.println("pool    :" + pool.getName());

            Map<zfs_prop_t, String> zfsPoolProps = pool.getZfsProperty(EnumSet.allOf(zfs_prop_t.class));
            for (zfs_prop_t prop : zfsPoolProps.keySet()) {
                System.out.println("zfs_prop_t " + prop + "(" + prop.ordinal()
                        + ") = " + zfsPoolProps.get(prop));
            }
        }

        ZFSObject o = zfs.open(ZFS_TEST_POOL_BASENAME);
        System.out.println("pool    :" + o.getName());

        Map<zfs_prop_t, String> zfsPoolProps = o.getZfsProperty(EnumSet.allOf(zfs_prop_t.class));
        for (zfs_prop_t prop : zfsPoolProps.keySet()) {
            System.out.println("zfs_prop_t " + prop + "(" + prop.ordinal()
                    + ") = " + zfsPoolProps.get(prop));
        }
    }

    public void testGetZpoolProperties() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        for (ZFSPool o : zfs.pools()) {
            ZFSFileSystem r = zfs.open(o.getName(), ZFSFileSystem.class);
            assertNotNull(r.getPool());
            System.out.println("name:" + o.getName() + " size:"
                    + o.getProperty(zpool_prop_t.ZPOOL_PROP_SIZE)
                    + " free:"
                    + o.getProperty(zpool_prop_t.ZPOOL_PROP_FREE));
            System.out.println("  status:"+o.getStatus());

            System.out.println(" size:"+o.getSize()+" used:"+o.getUsedSize()+" available:"+o.getAvailableSize());
        }
    }

    public void testAllow() {
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        ZFSFileSystem fs = zfs.create(dataSet, ZFSFileSystem.class);
        assertNotNull("ZFSObject was null for DataSet [" + dataSet + "]",
                fs);

        ACLBuilder acl = new ACLBuilder();
        acl.everyone().with(ZFSPermission.CREATE);
        // this fails if the permission being allowed here isn't already allowed to me
        fs.allow(acl);
        // for reasons beyond me, I can't unallow permissions that I just set above
        // fs.unallow(acl);
    }

    public void testfunc__zfs_perm_set() {
        /* Note: Given the presets and comments above, this routine is ONLY for testing the linkability of the function's ABI at the moment */
        if (ZFS_TEST_FUNCNAME.isEmpty())
            return;

        if (!ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_perm_set" + "\\b.*") && !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_allow" + "\\b.*") )
            return;

        System.out.println("testfunc__zfs_perm_set() : trying to do it...");

        ZFSObject fs = null;
        ACLBuilder acl = null;

        try {
            fs = zfs.open(dataSet);
        } catch (Exception e) { System.out.println("testfunc__zfs_perm_set() : exception: " + e.toString()); }
        try {
            acl = new ACLBuilder();
            acl.everyone().with(ZFSPermission.CREATE);
        } catch (Exception e) { System.out.println("testfunc__zfs_perm_set() : exception: " + e.toString()); }
        try {
            System.out.println("testfunc__zfs_perm_set() : starting the routine");
            fs.allow(acl);
            System.out.println("testfunc__zfs_perm_set() : passed the routine");
        } catch (Exception e) { System.out.println("testfunc__zfs_perm_set() : exception: " + e.toString()); }
    }

    public void testfunc__zfs_perm_remove() {
        /* Note: Given the presets and comments above, this routine is ONLY for testing the linkability of the function's ABI at the moment */
        if (ZFS_TEST_FUNCNAME.isEmpty())
            return;

        if (!ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_perm_remove" + "\\b.*") && !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_unallow" + "\\b.*") )
            return;

        System.out.println("testfunc__zfs_perm_remove() : trying to do it...");

        ZFSObject fs = null;
        ACLBuilder acl = null;

        try {
            fs = zfs.open(dataSet);
        } catch (Exception e) { System.out.println("testfunc__zfs_perm_remove() : exception: " + e.toString()); }
        try {
            acl = new ACLBuilder();
            acl.everyone().with(ZFSPermission.CREATE);
        } catch (Exception e) { System.out.println("testfunc__zfs_perm_remove() : exception: " + e.toString()); }
        try {
            System.out.println("testfunc__zfs_perm_remove() : starting the routine");
            fs.unallow(acl);
            System.out.println("testfunc__zfs_perm_remove() : passed the routine");
        } catch (Exception e) { System.out.println("testfunc__zfs_perm_remove() : exception: " + e.toString()); }
    }

    public void testInheritProperty() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty())
            return;

        ZFSFileSystem o  = zfs.create(dataSet, ZFSFileSystem.class);
        ZFSFileSystem o2 = zfs.create(dataSet+"/child",ZFSFileSystem.class);

        String property = "my:test";
        String time = String.valueOf(System.currentTimeMillis());
        o.setProperty(property, time);
        String v = o.getUserProperty(property);
        assertEquals(time,v);

        o2.inheritProperty(property);

        v = o2.getUserProperty(property);
        assertEquals(time,v);
    }

    public void test_zfsObject_exists() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty() && !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_destroy" + "\\b.*") && !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_create" + "\\b.*") )
            return;

        final ZFSObject fs1 = zfs.create(dataSet, ZFSFileSystem.class);

        assertNotNull("Prerequisite Failed ZFS dataset created was null ["
                + dataSet + "]", fs1);

        assertTrue("ZFS exists failed for freshly created dataset", zfs
                .exists(dataSet));
        assertTrue("ZFS exists failed for freshly created dataset of type FILESYSTEM", zfs.exists(
                dataSet, ZFSType.FILESYSTEM));

        fs1.destroy();
        assertFalse("ZFS exists failed for freshly destroyed dataset", zfs
                .exists(dataSet));
        assertFalse("ZFS exists failed for freshly destroyed dataset of type FILESYSTEM", zfs
                .exists(dataSet, ZFSType.FILESYSTEM));

        final ZFSObject fs2 = zfs.create(dataSet, ZFSFileSystem.class);

        assertNotNull("Prerequisite Failed ZFS dataset created was null ["
                + dataSet + "]", fs2);

        assertTrue("ZFS exists failed for freshly created dataset", zfs
                .exists(dataSet));
        assertTrue("ZFS exists failed for freshly created dataset of type FILESYSTEM", zfs.exists(
                dataSet, ZFSType.FILESYSTEM));

        fs2.destroy();
        assertFalse("ZFS exists failed for freshly destroyed dataset", zfs
                .exists(dataSet));
        assertFalse("ZFS exists failed for freshly destroyed dataset of type FILESYSTEM", zfs
                .exists(dataSet, ZFSType.FILESYSTEM));
    }

    public void test_zfsObject_isMounted() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty() &&
            !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_mount" + "\\b.*") &&
            !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_unmount" + "\\b.*") &&
            !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_create" + "\\b.*") )
            return;

        final ZFSFileSystem fs = zfs.create(dataSet, ZFSFileSystem.class);

        assertNotNull("Prerequisite Failed ZFS dataset created was null ["
                + dataSet + "]", fs);

        assertFalse("ZFS spec does not have dataset mounted at create", fs
                .isMounted());

        fs.mount();
        assertTrue("ZFS dataset mount failed, or isMounted failed", fs
                .isMounted());

        fs.unmount();
        assertFalse("ZFS dataset unmount failed, or isMounted failed", fs
                .isMounted());

        fs.mount();
        assertTrue("ZFS dataset mount failed, or isMounted failed", fs
                .isMounted());

        fs.unmount();
        assertFalse("ZFS dataset unmount failed, or isMounted failed", fs
                .isMounted());
    }

    public void xtest_zfsObject_isShared() {
        /* TODO: Real func name */
        if (!ZFS_TEST_FUNCNAME.isEmpty() &&
            !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_share" + "\\b.*") &&
            !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_unshare" + "\\b.*") &&
            !ZFS_TEST_FUNCNAME.matches(".*\\b" + "zfs_create" + "\\b.*") )
            return;

        final ZFSFileSystem fs = zfs.create(dataSet, ZFSFileSystem.class);

        assertNotNull("Prerequisite Failed ZFS dataset created was null ["
                + dataSet + "]", fs);

        assertFalse("ZFS spec does not have dataset shared at create", fs
                .isShared());

        fs.share();
        assertTrue("ZFS dataset share failed, or isShared failed", fs
                .isShared());

        fs.unshare();
        assertFalse("ZFS dataset unshare failed, or isShared failed", fs
                .isShared());

        fs.share();
        assertTrue("ZFS dataset share failed, or isShared failed", fs
                .isShared());

        fs.unshare();
        assertFalse("ZFS dataset unshare failed, or isShared failed", fs
                .isShared());
    }

}
