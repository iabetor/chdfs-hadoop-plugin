package com.qcloud.chdfs.fs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class CHDFSHadoopFileSystemAdapter extends FileSystemWithLockCleaner {
    static final String SCHEME = "ofs";
    private static final Logger log = LoggerFactory.getLogger(CHDFSHadoopFileSystemAdapter.class);
    private static final String MOUNT_POINT_ADDR_PATTERN =
            "^([a-zA-Z0-9-]+)\\.chdfs(-dualstack)?(\\.inner)?\\.([a-z0-9-]+)\\.([a-z0-9-.]+)";
    private static final String CHDFS_USER_APPID_KEY = "fs.ofs.user.appid";
    private static final String CHDFS_TMP_CACHE_DIR_KEY = "fs.ofs.tmp.cache.dir";
    private static final String CHDFS_META_SERVER_PORT_KEY = "fs.ofs.meta.server.port";
    private static final String CHDFS_META_TRANSFER_USE_TLS_KEY = "fs.ofs.meta.transfer.tls";
    private static final boolean DEFAULT_CHDFS_META_TRANSFER_USE_TLS = true;
    private static final int DEFAULT_CHDFS_META_SERVER_PORT = 443;

    private final CHDFSHadoopFileSystemJarLoader jarLoader = new CHDFSHadoopFileSystemJarLoader();
    private FileSystemWithLockCleaner actualImplFS = null;
    private URI uri = null;
    private Path workingDir = null;
    private long initStartMs;

    @Override
    public String getScheme() {
        return CHDFSHadoopFileSystemAdapter.SCHEME;
    }

    private static void initUGI(Configuration conf) throws IOException {
        if (!UserGroupInformation.isInitialized()) {
            synchronized (UserGroupInformation.class) {
                if (!UserGroupInformation.isInitialized()) {
                    UserGroupInformation.setConfiguration(conf);
                }
            }
        }
    }

    @Override
    public void initialize(URI name, Configuration conf) throws IOException {
        initUGI(conf);
        log.debug("CHDFSHadoopFileSystemAdapter adapter initialize");
        this.initStartMs = System.currentTimeMillis();
        log.debug("CHDFSHadoopFileSystemAdapter start-init-start time: {}", initStartMs);
        try {
            super.initialize(name, conf);
            this.setConf(conf);

            String mountPointAddr = name.getHost();
            if (mountPointAddr == null || !isValidMountPointAddr(mountPointAddr)) {
                String errMsg = String.format("mountPointAddr %s is invalid, fullUri: %s, exp. f4mabcdefgh-xyzw.chdfs"
                                + ".ap-guangzhou.myqcloud.com", mountPointAddr == null ? "null" : mountPointAddr,
                        name.toString());
                log.error(errMsg);
                throw new IOException(errMsg);
            }

            long appid = getAppid(conf);
            int jarPluginServerPort = getJarPluginServerPort(conf);
            String tmpDirPath = initCacheTmpDir(conf);
            boolean jarPluginServerHttpsFlag = isJarPluginServerHttps(conf);

            initJarLoadWithRetry(mountPointAddr, appid, jarPluginServerPort, tmpDirPath, jarPluginServerHttpsFlag);

            this.actualImplFS = jarLoader.getActualFileSystem();
            if (this.actualImplFS == null) {
                // should never reach here
                throw new IOException("impl filesystem is null");
            }

            long actualInitStartMs = System.currentTimeMillis();
            this.actualImplFS.initialize(name, conf);
            log.debug("init actual file system, [elapse-ms: {}]", System.currentTimeMillis() - actualInitStartMs);
            this.uri = this.actualImplFS.getUri();
            this.workingDir = this.actualImplFS.getWorkingDirectory();
        } catch (IOException ioe) {
            log.error("initialize failed! a ioException occur!", ioe);
            throw ioe;
        } catch (Exception e) {
            log.error("initialize failed! a unexpected exception occur!", e);
            throw new IOException("initialize failed! oops! a unexpected exception occur! " + e.toString(), e);
        }
        log.debug("total init file system, [elapse-ms: {}]", System.currentTimeMillis() - initStartMs);
    }

    boolean isValidMountPointAddr(String mountPointAddr) {
        return Pattern.matches(MOUNT_POINT_ADDR_PATTERN, mountPointAddr);
    }

    private long getAppid(Configuration conf) throws IOException {
        long appid = 0;
        try {
            appid = conf.getLong(CHDFS_USER_APPID_KEY, 0);
        } catch (NumberFormatException e) {
            throw new IOException(String.format("config for %s is invalid appid number", CHDFS_USER_APPID_KEY));
        }
        if (appid <= 0) {
            throw new IOException(
                    String.format("config for %s is missing or invalid appid number", CHDFS_USER_APPID_KEY));
        }
        return appid;
    }

    private int getJarPluginServerPort(Configuration conf) {
        return conf.getInt(CHDFS_META_SERVER_PORT_KEY, DEFAULT_CHDFS_META_SERVER_PORT);
    }

    private String initCacheTmpDir(Configuration conf) throws IOException {
        String chdfsTmpCacheDirPath = conf.get(CHDFS_TMP_CACHE_DIR_KEY);
        if (chdfsTmpCacheDirPath == null) {
            String errMsg = String.format("chdfs config %s is missing", CHDFS_TMP_CACHE_DIR_KEY);
            log.error(errMsg);
            throw new IOException(errMsg);
        }
        if (!chdfsTmpCacheDirPath.startsWith("/")) {
            String errMsg = String.format("chdfs config [%s: %s] must be absolute path", CHDFS_TMP_CACHE_DIR_KEY,
                    chdfsTmpCacheDirPath);
            log.error(errMsg);
            throw new IOException(errMsg);
        }

        File chdfsTmpCacheDir = new File(chdfsTmpCacheDirPath);
        if (!chdfsTmpCacheDir.exists()) {
            // judge exists again, may many map-reduce processes create cache dir parallel
            if (!chdfsTmpCacheDir.mkdirs() && !chdfsTmpCacheDir.exists()) {
                String errMsg = String.format("mkdir for chdfs tmp dir %s failed", chdfsTmpCacheDir.getAbsolutePath());
                log.error(errMsg);
                throw new IOException(errMsg);
            }
            chdfsTmpCacheDir.setReadable(true, false);
            chdfsTmpCacheDir.setWritable(true, false);
            chdfsTmpCacheDir.setExecutable(true, false);
        }

        if (!chdfsTmpCacheDir.isDirectory()) {
            String errMsg = String.format("chdfs config [%s: %s] is invalid directory", CHDFS_TMP_CACHE_DIR_KEY,
                    chdfsTmpCacheDir.getAbsolutePath());
            log.error(errMsg);
            throw new IOException(errMsg);
        }

        if (!chdfsTmpCacheDir.canRead()) {
            String errMsg = String.format("chdfs config [%s: %s] is not readable", CHDFS_TMP_CACHE_DIR_KEY,
                    chdfsTmpCacheDirPath);
            log.error(errMsg);
            throw new IOException(errMsg);
        }

        if (!chdfsTmpCacheDir.canWrite()) {
            String errMsg = String.format("chdfs config [%s: %s] is not writeable", CHDFS_TMP_CACHE_DIR_KEY,
                    chdfsTmpCacheDirPath);
            log.error(errMsg);
            throw new IOException(errMsg);
        }
        return chdfsTmpCacheDirPath;
    }

    private boolean isJarPluginServerHttps(Configuration conf) {
        return conf.getBoolean(CHDFS_META_TRANSFER_USE_TLS_KEY, DEFAULT_CHDFS_META_TRANSFER_USE_TLS);
    }

    private void initJarLoadWithRetry(String mountPointAddr, long appid, int jarPluginServerPort, String tmpDirPath,
            boolean jarPluginServerHttps) throws IOException {
        int maxRetry = 5;
        for (int retryIndex = 0; retryIndex <= maxRetry; retryIndex++) {
            try {
                jarLoader.init(mountPointAddr, appid, jarPluginServerPort, tmpDirPath, jarPluginServerHttps);
                return;
            } catch (IOException e) {
                if (retryIndex < maxRetry) {
                    log.warn(String.format("init chdfs impl failed, we will retry again, retryInfo: %d/%d", retryIndex,
                            maxRetry), e);
                } else {
                    log.error("init chdfs impl failed", e);
                    throw new IOException("init chdfs impl failed", e);
                }
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(500, 2000));
            } catch (InterruptedException ignore) {
                // ignore
            }
        }
    }

    @java.lang.Override
    public java.net.URI getUri() {
        return this.uri;
    }

    @Override
    public FileChecksum getFileChecksum(Path f, long length) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getFileChecksum(f, length);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getFileChecksum failed! a unexpected exception occur!", e);
            throw new IOException("getFileChecksum failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public FSDataInputStream open(Path f, int bufferSize) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.open(f, bufferSize);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("open failed! a unexpected exception occur!", e);
            throw new IOException("open failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public FSDataOutputStream createNonRecursive(Path f, FsPermission permission, EnumSet<CreateFlag> flags,
            int bufferSize, short replication, long blockSize, Progressable progress) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.createNonRecursive(f, permission, flags, bufferSize, replication, blockSize,
                    progress);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("createNonRecursive failed! a unexpected exception occur!", e);
            throw new IOException("createNonRecursive failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize,
            short replication, long blockSize, Progressable progress) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.create(f, permission, overwrite, bufferSize, replication, blockSize, progress);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("create failed! a unexpected exception occur!", e);
            throw new IOException("create failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public FSDataOutputStream append(Path f, int bufferSize, Progressable progress) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.append(f, bufferSize, progress);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("append failed! a unexpected exception occur!", e);
            throw new IOException("append failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public boolean truncate(Path f, long newLength) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.truncate(f, newLength);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("truncate failed! a unexpected exception occur!", e);
            throw new IOException("truncate failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public boolean rename(Path src, Path dst) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.rename(src, dst);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("rename failed! a unexpected exception occur!", e);
            throw new IOException("rename failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public boolean delete(Path f, boolean recursive) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.delete(f, recursive);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("delete failed! a unexpected exception occur!", e);
            throw new IOException("delete failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public boolean deleteOnExit(Path f) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.deleteOnExit(f);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("deleteOnExit failed! a unexpected exception occur!", e);
            throw new IOException("deleteOnExit failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public boolean cancelDeleteOnExit(Path f) {
        if (this.actualImplFS == null) {
            throw new RuntimeException("please init the fileSystem first!");
        }
        return this.actualImplFS.cancelDeleteOnExit(f);
    }


    @java.lang.Override
    public FileStatus[] listStatus(Path f) throws FileNotFoundException, IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.listStatus(f);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("listStatus failed! a unexpected exception occur!", e);
            throw new IOException("listStatus failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public Path getWorkingDirectory() {
        return this.workingDir;
    }

    @java.lang.Override
    public void setWorkingDirectory(Path new_dir) {
        if (this.actualImplFS == null) {
            this.workingDir = new_dir;
            log.warn("fileSystem is not init yet!");
        } else {
            this.workingDir = new_dir;
            this.actualImplFS.setWorkingDirectory(new_dir);
        }
    }

    @Override
    public Path getHomeDirectory() {
        if (this.actualImplFS == null) {
            return super.getHomeDirectory();
        }
        return this.actualImplFS.getHomeDirectory();
    }

    @java.lang.Override
    public boolean mkdirs(Path f, FsPermission permission) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.mkdirs(f, permission);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("mkdir failed! a unexpected exception occur!", e);
            throw new IOException("mkdir failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @java.lang.Override
    public FileStatus getFileStatus(Path f) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getFileStatus(f);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getFileStatus failed! a unexpected exception occur!", e);
            throw new IOException("getFileStatus failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void setPermission(Path p, FsPermission permission) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.setPermission(p, permission);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("setPermission failed! a unexpected exception occur!", e);
            throw new IOException("setPermission failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void setOwner(Path p, String username, String groupname) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.setOwner(p, username, groupname);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("setOwner failed! a unexpected exception occur!", e);
            throw new IOException("setOwner failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void setTimes(Path p, long mtime, long atime) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.setTimes(p, mtime, atime);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("setTimes failed! a unexpected exception occur!", e);
            throw new IOException("setTimes failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void setXAttr(Path path, String name, byte[] value, EnumSet<XAttrSetFlag> flag) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.setXAttr(path, name, value, flag);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("setXAttr failed! a unexpected exception occur!", e);
            throw new IOException("setXAttr failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public byte[] getXAttr(Path path, String name) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getXAttr(path, name);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getXAttr failed! a unexpected exception occur!", e);
            throw new IOException("getXAttr failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public Map<String, byte[]> getXAttrs(Path path) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getXAttrs(path);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getXAttrs failed! a unexpected exception occur!", e);
            throw new IOException("getXAttrs failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public Map<String, byte[]> getXAttrs(Path path, List<String> names) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getXAttrs(path, names);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getXAttrs failed! a unexpected exception occur!", e);
            throw new IOException("getXAttrs failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public List<String> listXAttrs(Path path) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.listXAttrs(path);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("listXAttrs failed! a unexpected exception occur!", e);
            throw new IOException("listXAttrs failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void removeXAttr(Path path, String name) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.removeXAttr(path, name);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("removeXAttr failed! a unexpected exception occur!", e);
            throw new IOException("removeXAttr failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public Path createSnapshot(Path path, String snapshotName) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.createSnapshot(path, snapshotName);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("createSnapshot failed! a unexpected exception occur!", e);
            throw new IOException("createSnapshot failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void renameSnapshot(Path path, String snapshotOldName, String snapshotNewName) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.renameSnapshot(path, snapshotOldName, snapshotNewName);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("renameSnapshot failed! a unexpected exception occur!", e);
            throw new IOException("renameSnapshot failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void deleteSnapshot(Path path, String snapshotName) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.deleteSnapshot(path, snapshotName);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("deleteSnapshot failed! a unexpected exception occur!", e);
            throw new IOException("deleteSnapshot failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void createSymlink(Path target, Path link, boolean createParent)
            throws AccessControlException, FileAlreadyExistsException, FileNotFoundException,
                   ParentNotDirectoryException, UnsupportedFileSystemException, IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.createSymlink(target, link, createParent);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("createSymlink failed! a unexpected exception occur!", e);
            throw new IOException("createSymlink failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public boolean supportsSymlinks() {
        if (this.actualImplFS == null) {
            return false;
        }
        return this.actualImplFS.supportsSymlinks();
    }

    @Override
    public Path getLinkTarget(Path f) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getLinkTarget(f);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getLinkTarget failed! a unexpected exception occur!", e);
            throw new IOException("getLinkTarget failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void modifyAclEntries(Path path, List<AclEntry> aclSpec) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.modifyAclEntries(path, aclSpec);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("modifyAclEntries failed! a unexpected exception occur!", e);
            throw new IOException("modifyAclEntries failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void removeAclEntries(Path path, List<AclEntry> aclSpec) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.removeAclEntries(path, aclSpec);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("removeAclEntries failed! a unexpected exception occur!", e);
            throw new IOException("removeAclEntries failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void removeDefaultAcl(Path path) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.removeDefaultAcl(path);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("removeDefaultAcl failed! a unexpected exception occur!", e);
            throw new IOException("removeDefaultAcl failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void removeAcl(Path path) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.removeAcl(path);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("removeAcl failed! a unexpected exception occur!", e);
            throw new IOException("removeAcl failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void setAcl(Path path, List<AclEntry> aclSpec) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.setAcl(path, aclSpec);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("setAcl failed! a unexpected exception occur!", e);
            throw new IOException("setAcl failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public AclStatus getAclStatus(Path path) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getAclStatus(path);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getAclStatus failed! a unexpected exception occur!", e);
            throw new IOException("getAclStatus failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void concat(Path trg, Path[] psrcs) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.concat(trg, psrcs);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("concat failed! a unexpected exception occur!", e);
            throw new IOException("concat failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public Token<?> getDelegationToken(String renewer) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getDelegationToken(renewer);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getDelegationToken failed! a unexpected exception occur!", e);
            throw new IOException("getDelegationToken failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public String getCanonicalServiceName() {
        if (this.actualImplFS == null) {
            return null;
        } else {
            return this.actualImplFS.getCanonicalServiceName();
        }
    }

    @Override
    public ContentSummary getContentSummary(Path f) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            return this.actualImplFS.getContentSummary(f);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("getContentSummary failed! a unexpected exception occur!", e);
            throw new IOException("getContentSummary failed! a unexpected exception occur! " + e.getMessage());
        }
    }

    @Override
    public void releaseFileLock(Path p) throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            this.actualImplFS.releaseFileLock(p);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.error("releaseFileLock failed! a unexpected exception occur!", e);
            throw new IOException("releaseFileLock failed! a unexpected exception occur! " + e.getMessage());
        }
    }


    @Override
    public void close() throws IOException {
        if (this.actualImplFS == null) {
            throw new IOException("please init the fileSystem first!");
        }
        try {
            super.close();
            long actualCloseStartTimeMs = System.currentTimeMillis();
            this.actualImplFS.close();
            log.debug("actual-file-system-close usedTime: {}", System.currentTimeMillis() - actualCloseStartTimeMs);
        } catch (IOException ioe) {
            log.error("close fileSystem occur a ioException!", ioe);
            throw ioe;
        } catch (Exception e) {
            log.error("close fileSystem failed! a unexpected exception occur!", e);
            throw new IOException("close fileSystem failed! a unexpected exception occur! " + e.getMessage());
        }
        long endMs = System.currentTimeMillis();
        log.debug("end-close time: {}, total-used-time: {}", endMs, endMs - initStartMs);
    }
}
