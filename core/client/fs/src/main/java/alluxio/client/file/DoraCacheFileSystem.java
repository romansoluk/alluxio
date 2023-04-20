/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file;

import alluxio.AlluxioURI;
import alluxio.CloseableSupplier;
import alluxio.PositionReader;
import alluxio.client.ReadType;
import alluxio.client.file.dora.DoraCacheClient;
import alluxio.client.file.dora.WorkerLocationPolicy;
import alluxio.client.file.ufs.UfsBaseFileSystem;
import alluxio.conf.AlluxioConfiguration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AlluxioException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.FileIncompleteException;
import alluxio.exception.InvalidPathException;
import alluxio.exception.OpenDirectoryException;
import alluxio.exception.runtime.AlluxioRuntimeException;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.ExistsPOptions;
import alluxio.grpc.GetStatusPOptions;
import alluxio.grpc.ListStatusPOptions;
import alluxio.grpc.OpenFilePOptions;
import alluxio.grpc.RenamePOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.proto.dataserver.Protocol;
import alluxio.util.FileSystemOptionsUtils;
import alluxio.util.io.PathUtils;
import alluxio.wire.BlockInfo;
import alluxio.wire.BlockLocation;
import alluxio.wire.BlockLocationInfo;
import alluxio.wire.FileBlockInfo;
import alluxio.wire.WorkerNetAddress;

import com.codahale.metrics.Counter;
import com.google.common.collect.ImmutableList;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dora Cache file system implementation.
 */
public class DoraCacheFileSystem extends DelegatingFileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(DoraCacheFileSystem.class);
  public static final int DUMMY_MOUNT_ID = 0;
  private static final Counter UFS_FALLBACK_COUNTER = MetricsSystem.counter(
      MetricKey.CLIENT_UFS_FALLBACK_COUNT.getName());
  private final DoraCacheClient mDoraClient;
  private final FileSystemContext mFsContext;
  private final boolean mMetadataCacheEnabled;
  private final long mDefaultVirtualBlockSize;

  /**
   * Wraps a file system instance to forward messages.
   *
   * @param fs the underlying file system
   * @param context
   */
  public DoraCacheFileSystem(FileSystem fs, FileSystemContext context) {
    super(fs);
    mDoraClient = new DoraCacheClient(context, new WorkerLocationPolicy(2000));
    mFsContext = context;
    mMetadataCacheEnabled = context.getClusterConf()
        .getBoolean(PropertyKey.DORA_CLIENT_METADATA_CACHE_ENABLED);
    mDefaultVirtualBlockSize = context.getClusterConf()
        .getBytes(PropertyKey.USER_BLOCK_SIZE_BYTES_DEFAULT);
  }

  @Override
  public URIStatus getStatus(AlluxioURI path, GetStatusPOptions options)
      throws IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);

    if (!mMetadataCacheEnabled) {
      return mDelegatedFileSystem.getStatus(ufsFullPath, options);
    }
    try {
      GetStatusPOptions mergedOptions = FileSystemOptionsUtils.getStatusDefaults(
          mFsContext.getPathConf(path)).toBuilder().mergeFrom(options).build();

      return mDoraClient.getStatus(ufsFullPath.toString(), mergedOptions);
    } catch (RuntimeException ex) {
      if (ex instanceof StatusRuntimeException) {
        if (((StatusRuntimeException) ex).getStatus().getCode() == Status.NOT_FOUND.getCode()) {
          throw new FileNotFoundException();
        }
      }
      UFS_FALLBACK_COUNTER.inc();
      LOG.debug("Dora client get status error ({} times). Fall back to UFS.",
          UFS_FALLBACK_COUNTER.getCount(), ex);
      return mDelegatedFileSystem.getStatus(ufsFullPath, options);
    }
  }

  @Override
  public FileInStream openFile(AlluxioURI path, OpenFilePOptions options)
      throws IOException, AlluxioException {
    return openFile(getStatus(path), options);
  }

  @Override
  public FileInStream openFile(URIStatus status, OpenFilePOptions options)
      throws IOException, AlluxioException {
    AlluxioURI path = new AlluxioURI(status.getPath());
    if (status.isFolder()) {
      throw new OpenDirectoryException(path);
    }
    if (!status.isCompleted()) {
      throw new FileIncompleteException(path);
    }
    AlluxioConfiguration conf = mFsContext.getPathConf(path);
    OpenFilePOptions mergedOptions = FileSystemOptionsUtils.openFileDefaults(conf)
        .toBuilder().mergeFrom(options).build();
    try {
      Protocol.OpenUfsBlockOptions openUfsBlockOptions =
          Protocol.OpenUfsBlockOptions.newBuilder().setUfsPath(status.getUfsPath())
              .setOffsetInFile(0).setBlockSize(status.getLength())
              .setMaxUfsReadConcurrency(mergedOptions.getMaxUfsReadConcurrency())
              .setNoCache(!ReadType.fromProto(mergedOptions.getReadType()).isCache())
              .setMountId(DUMMY_MOUNT_ID)
              .build();
      return mDoraClient.getInStream(status, openUfsBlockOptions);
    } catch (RuntimeException ex) {
      UFS_FALLBACK_COUNTER.inc();
      LOG.debug("Dora client open file error ({} times). Fall back to UFS.",
          UFS_FALLBACK_COUNTER.getCount(), ex);
      return mDelegatedFileSystem.openFile(status, mergedOptions);
    }
  }

  @Override
  public PositionReader openPositionRead(AlluxioURI path, OpenFilePOptions options) {
    try {
      return openPositionRead(getStatus(path), options);
    } catch (IOException | AlluxioException e) {
      throw AlluxioRuntimeException.from(e);
    }
  }

  @Override
  public PositionReader openPositionRead(URIStatus status, OpenFilePOptions options) {
    AlluxioURI path = new AlluxioURI(status.getPath());
    if (status.isFolder()) {
      throw AlluxioRuntimeException.from(new OpenDirectoryException(path));
    }
    if (!status.isCompleted()) {
      throw AlluxioRuntimeException.from(new FileIncompleteException(path));
    }
    AlluxioConfiguration conf = mFsContext.getPathConf(path);
    OpenFilePOptions mergedOptions = FileSystemOptionsUtils.openFileDefaults(conf)
        .toBuilder().mergeFrom(options).build();
    Protocol.OpenUfsBlockOptions openUfsBlockOptions =
        Protocol.OpenUfsBlockOptions.newBuilder().setUfsPath(status.getUfsPath())
            .setOffsetInFile(0).setBlockSize(status.getLength())
            .setMaxUfsReadConcurrency(mergedOptions.getMaxUfsReadConcurrency())
            .setNoCache(!ReadType.fromProto(mergedOptions.getReadType()).isCache())
            .setMountId(DUMMY_MOUNT_ID)
            .build();
    return mDoraClient.createNettyPositionReader(status, openUfsBlockOptions,
        new CloseableSupplier<>(() ->
            mDelegatedFileSystem.openPositionRead(status, mergedOptions)));
  }

  @Override
  public List<URIStatus> listStatus(AlluxioURI path, ListStatusPOptions options)
      throws FileDoesNotExistException, IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);
    ufsFullPath = new AlluxioURI(PathUtils.normalizePath(ufsFullPath.toString(), "/"));

    try {
      return mDoraClient.listStatus(ufsFullPath.toString(), options);
    } catch (RuntimeException ex) {
      if (ex instanceof StatusRuntimeException) {
        if (((StatusRuntimeException) ex).getStatus().getCode() == Status.NOT_FOUND.getCode()) {
          return Collections.emptyList();
        }
      }

      UFS_FALLBACK_COUNTER.inc();
      LOG.debug("Dora client list status error ({} times). Fall back to UFS.",
          UFS_FALLBACK_COUNTER.getCount(), ex);
      return mDelegatedFileSystem.listStatus(ufsFullPath, options);
    }
  }

  @Override
  public FileOutStream createFile(AlluxioURI path, CreateFilePOptions options)
      throws FileAlreadyExistsException, InvalidPathException, IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);
    LOG.warn("Dora Client does not support create/write. This is only for test.");
    return mDelegatedFileSystem.createFile(ufsFullPath, options);
  }

  @Override
  public void createDirectory(AlluxioURI path, CreateDirectoryPOptions options)
      throws FileAlreadyExistsException, InvalidPathException, IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);
    LOG.warn("Dora Client does not support create/write. This is only for test.");

    mDelegatedFileSystem.createDirectory(ufsFullPath, options);
  }

  @Override
  public void rename(AlluxioURI src, AlluxioURI dst, RenamePOptions options)
      throws FileDoesNotExistException, IOException, AlluxioException {
    AlluxioURI srcUfsFullPath = convertAlluxioPathToUFSPath(src);
    AlluxioURI dstUfsFullPath = convertAlluxioPathToUFSPath(dst);
    LOG.warn("Dora Client does not support create/write. This is only for test.");

    mDelegatedFileSystem.rename(srcUfsFullPath, dstUfsFullPath, options);
  }

  @Override
  public void iterateStatus(AlluxioURI path, ListStatusPOptions options,
      Consumer<? super URIStatus> action)
      throws FileDoesNotExistException, IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);

    mDelegatedFileSystem.iterateStatus(ufsFullPath, options, action);
  }

  @Override
  public boolean exists(AlluxioURI path, ExistsPOptions options)
      throws InvalidPathException, IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);

    return mDelegatedFileSystem.exists(ufsFullPath, options);
  }

  @Override
  public void setAttribute(AlluxioURI path, SetAttributePOptions options)
      throws FileDoesNotExistException, IOException, AlluxioException {
    AlluxioURI ufsFullPath = convertAlluxioPathToUFSPath(path);
    LOG.warn("Dora Client does not support create/write. This is only for test.");

    mDelegatedFileSystem.setAttribute(ufsFullPath, options);
  }

  /**
   * Converts the Alluxio based path to UfsBaseFileSystem based path if needed.
   *
   * UfsBaseFileSystem expects absolute/full file path. The Dora Worker
   * expects absolute/full file path, too. So we need to convert the input path from Alluxio
   * relative path to full UFS path if it is an Alluxio relative path.
   * We do this by checking if the path is leading with the UFS root. If the input path
   * is already considered to be UFS path, it should be leading a UFS path with appropriate scheme.
   * If local file system is used, please add "file://" scheme before the path.
   *
   * @param alluxioPath Alluxio based path
   * @return UfsBaseFileSystem based full path
   */
  private AlluxioURI convertAlluxioPathToUFSPath(AlluxioURI alluxioPath) {
    if (mDelegatedFileSystem instanceof UfsBaseFileSystem) {
      UfsBaseFileSystem under = (UfsBaseFileSystem) mDelegatedFileSystem;
      AlluxioURI rootUFS = under.getRootUFS();
      try {
        if (rootUFS.isAncestorOf(alluxioPath)) {
          // Treat this path as a full UFS path.
          return alluxioPath;
        }
      } catch (InvalidPathException e) {
        LOG.error("Invalid path {}", alluxioPath);
        throw new RuntimeException(e);
      }

      // Treat this path as Alluxio relative, and add the UFS root before it.
      String ufsFullPath = PathUtils.concatPath(rootUFS, alluxioPath.toString());
      if (alluxioPath.isRoot()) {
        ufsFullPath = ufsFullPath + AlluxioURI.SEPARATOR;
      }

      return new AlluxioURI(ufsFullPath);
    } else {
      return alluxioPath;
    }
  }

  @Override
  public List<BlockLocationInfo> getBlockLocations(AlluxioURI path)
      throws IOException, AlluxioException {
    AlluxioURI ufsPath = convertAlluxioPathToUFSPath(path);
    URIStatus status = mDoraClient.getStatus(ufsPath.toString(),
        FileSystemOptionsUtils.getStatusDefaults(mFsContext.getPathConf(path)));
    return getBlockLocations(status);
  }

  @Override
  public List<BlockLocationInfo> getBlockLocations(URIStatus status)
      throws IOException, AlluxioException {
    AlluxioURI ufsPath = convertAlluxioPathToUFSPath(new AlluxioURI(status.getUfsPath()));
    WorkerNetAddress workerNetAddress = mDoraClient.getWorkerNetAddress(ufsPath.toString());
    // Dora does not have blocks; to apps who need block location info, we split multiple virtual
    // blocks from a file according to a fixed size
    long blockSize = mDefaultVirtualBlockSize;
    long length = status.getLength();
    int blockNum = length == blockSize ? 1 : (int) (length / blockSize) + 1;
    // construct BlockLocation
    ImmutableList.Builder<BlockLocationInfo> listBuilder = ImmutableList.builder();
    for (int i = 0; i < blockNum; i++) {
      long offset = i * blockSize;
      BlockLocation blockLocation = new BlockLocation().setWorkerAddress(workerNetAddress);
      BlockInfo bi = new BlockInfo()
          // a dummy block ID which shouldn't be used to identify the block
          .setBlockId(i + 1)
          .setLength(Math.min(blockSize, status.getLength() - offset))
          .setLocations(ImmutableList.of(blockLocation));

      FileBlockInfo fbi = new FileBlockInfo()
          .setUfsLocations(ImmutableList.of(ufsPath.toString()))
          .setBlockInfo(bi)
          .setOffset(offset);

      BlockLocationInfo blockLocationInfo =
          new BlockLocationInfo(fbi, ImmutableList.of(workerNetAddress));
      listBuilder.add(blockLocationInfo);
    }
    return listBuilder.build();
  }
}
