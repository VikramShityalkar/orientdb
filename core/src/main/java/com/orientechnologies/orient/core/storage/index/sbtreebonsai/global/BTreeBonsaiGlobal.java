package com.orientechnologies.orient.core.storage.index.sbtreebonsai.global;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.index.OAlwaysGreaterKey;
import com.orientechnologies.orient.core.index.OAlwaysLessKey;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperation;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import com.orientechnologies.orient.core.storage.impl.local.paginated.base.ODurableComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BTreeBonsaiGlobal extends ODurableComponent {

  private static final OAlwaysLessKey ALWAYS_LESS_KEY = new OAlwaysLessKey();
  private static final OAlwaysGreaterKey ALWAYS_GREATER_KEY = new OAlwaysGreaterKey();

  private static final int MAX_PATH_LENGTH =
      OGlobalConfiguration.SBTREE_MAX_DEPTH.getValueAsInteger();

  private static final int ENTRY_POINT_INDEX = 0;
  private static final long ROOT_INDEX = 1;

  private volatile long fileId;

  public BTreeBonsaiGlobal(
      final OAbstractPaginatedStorage storage, final String name, final String fileExtension) {
    super(storage, name, fileExtension, name + fileExtension);
  }

  public void create(final OAtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        (operation) -> {
          acquireExclusiveLock();
          try {
            fileId = addFile(atomicOperation, getFullName());

            final OCacheEntry entryPointCacheEntry = addPage(atomicOperation, fileId);
            try {
              final EntryPoint entryPoint = new EntryPoint(entryPointCacheEntry);
              entryPoint.init();
            } finally {
              releasePageFromWrite(atomicOperation, entryPointCacheEntry);
            }

            final OCacheEntry rootCacheEntry = addPage(atomicOperation, fileId);
            try {
              final Bucket rootBucket = new Bucket(rootCacheEntry);
              rootBucket.init(true);
            } finally {
              releasePageFromWrite(atomicOperation, rootCacheEntry);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public void load(final String name) {
    acquireExclusiveLock();
    try {
      final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();

      fileId = openFile(atomicOperation, getFullName());
    } catch (final IOException e) {
      throw OException.wrapException(
          new OStorageException("Exception during loading of rid bag " + name), e);
    } finally {
      releaseExclusiveLock();
    }
  }

  public void delete(final OAtomicOperation atomicOperation) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            deleteFile(atomicOperation, fileId);
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  public int get(final int targetCluster, final long targetPosition) {
    atomicOperationsManager.acquireReadLock(this);
    try {
      acquireSharedLock();
      try {
        final OAtomicOperation atomicOperation = OAtomicOperationsManager.getCurrentOperation();
        final EdgeKey key = new EdgeKey(targetCluster, targetPosition);
        final BucketSearchResult bucketSearchResult = findBucket(key, atomicOperation);
        if (bucketSearchResult.itemIndex < 0) {
          return -1;
        }

        final long pageIndex = bucketSearchResult.pageIndex;
        final OCacheEntry keyBucketCacheEntry =
            loadPageForRead(atomicOperation, fileId, pageIndex, false);
        try {
          final Bucket keyBucket = new Bucket(keyBucketCacheEntry);
          return keyBucket.getValue(bucketSearchResult.itemIndex);
        } finally {
          releasePageFromRead(atomicOperation, keyBucketCacheEntry);
        }
      } finally {
        releaseSharedLock();
      }
    } catch (final IOException e) {
      throw OException.wrapException(
          new OStorageException(
              "Error during retrieving  of value for rid bag with name " + getName()),
          e);
    } finally {
      atomicOperationsManager.releaseReadLock(this);
    }
  }

  public void put(
      final OAtomicOperation atomicOperation,
      final int targetCluster,
      final long targetPosition,
      final int value) {
    executeInsideComponentOperation(
        atomicOperation,
        operation -> {
          acquireExclusiveLock();
          try {
            final EdgeKey key = new EdgeKey(targetCluster, targetPosition);
            final byte[] serializedKey =
                EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(key, (Object[]) null);
            UpdateBucketSearchResult bucketSearchResult = findBucketForUpdate(key, atomicOperation);

            OCacheEntry keyBucketCacheEntry =
                loadPageForWrite(
                    atomicOperation, fileId, bucketSearchResult.getLastPathItem(), false, true);
            Bucket keyBucket = new Bucket(keyBucketCacheEntry);
            final byte[] oldRawValue;

            if (bucketSearchResult.itemIndex > -1) {
              oldRawValue = keyBucket.getRawValue(bucketSearchResult.itemIndex);
            } else {
              oldRawValue = null;
            }

            final byte[] serializedValue =
                IntSerializer.INSTANCE.serializeNativeAsWhole(value, (Object[]) null);

            int insertionIndex;
            final int sizeDiff;
            if (bucketSearchResult.itemIndex >= 0) {
              assert oldRawValue != null;

              if (oldRawValue.length == serializedValue.length) {
                keyBucket.updateValue(
                    bucketSearchResult.itemIndex, serializedValue, serializedKey.length);
                releasePageFromWrite(atomicOperation, keyBucketCacheEntry);
                return;
              } else {
                keyBucket.removeLeafEntry(
                    bucketSearchResult.itemIndex, serializedKey.length, serializedValue.length);
                insertionIndex = bucketSearchResult.itemIndex;
                sizeDiff = 0;
              }
            } else {
              insertionIndex = -bucketSearchResult.itemIndex - 1;
              sizeDiff = 1;
            }

            while (!keyBucket.addLeafEntry(insertionIndex, serializedKey, serializedValue)) {
              bucketSearchResult =
                  splitBucket(
                      keyBucket,
                      keyBucketCacheEntry,
                      bucketSearchResult.path,
                      bucketSearchResult.insertionIndexes,
                      insertionIndex,
                      atomicOperation);

              insertionIndex = bucketSearchResult.itemIndex;

              final long pageIndex = bucketSearchResult.getLastPathItem();

              if (pageIndex != keyBucketCacheEntry.getPageIndex()) {
                releasePageFromWrite(atomicOperation, keyBucketCacheEntry);

                keyBucketCacheEntry =
                    loadPageForWrite(atomicOperation, fileId, pageIndex, false, true);
              }

              //noinspection ObjectAllocationInLoop
              keyBucket = new Bucket(keyBucketCacheEntry);
            }

            releasePageFromWrite(atomicOperation, keyBucketCacheEntry);

            if (sizeDiff != 0) {
              updateSize(sizeDiff, atomicOperation);
            }
          } finally {
            releaseExclusiveLock();
          }
        });
  }

  private UpdateBucketSearchResult splitBucket(
      final Bucket bucketToSplit,
      final OCacheEntry entryToSplit,
      final List<Long> path,
      final List<Integer> itemPointers,
      final int keyIndex,
      final OAtomicOperation atomicOperation)
      throws IOException {
    final boolean splitLeaf = bucketToSplit.isLeaf();
    final int bucketSize = bucketToSplit.size();

    final int indexToSplit = bucketSize >>> 1;
    final EdgeKey separationKey = bucketToSplit.getKey(indexToSplit);
    final List<byte[]> rightEntries = new ArrayList<>(indexToSplit);

    final int startRightIndex = splitLeaf ? indexToSplit : indexToSplit + 1;

    for (int i = startRightIndex; i < bucketSize; i++) {
      rightEntries.add(bucketToSplit.getRawEntry(i));
    }

    if (entryToSplit.getPageIndex() != ROOT_INDEX) {
      return splitNonRootBucket(
          path,
          itemPointers,
          keyIndex,
          entryToSplit.getPageIndex(),
          bucketToSplit,
          splitLeaf,
          indexToSplit,
          separationKey,
          rightEntries,
          atomicOperation);
    } else {
      return splitRootBucket(
          keyIndex,
          entryToSplit,
          bucketToSplit,
          splitLeaf,
          indexToSplit,
          separationKey,
          rightEntries,
          atomicOperation);
    }
  }

  private UpdateBucketSearchResult splitNonRootBucket(
      final List<Long> path,
      final List<Integer> itemPointers,
      final int keyIndex,
      final long pageIndex,
      final Bucket bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final EdgeKey separationKey,
      final List<byte[]> rightEntries,
      final OAtomicOperation atomicOperation)
      throws IOException {

    final OCacheEntry rightBucketEntry;
    final OCacheEntry entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
    try {
      final EntryPoint entryPoint = new EntryPoint(entryPointCacheEntry);
      int pageSize = entryPoint.getPagesSize();

      if (pageSize < getFilledUpTo(atomicOperation, fileId) - 1) {
        pageSize++;
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false, false);
        entryPoint.setPagesSize(pageSize);
      } else {
        assert pageSize == getFilledUpTo(atomicOperation, fileId) - 1;

        rightBucketEntry = addPage(atomicOperation, fileId);
        entryPoint.setPagesSize((int) rightBucketEntry.getPageIndex());
      }
    } finally {
      releasePageFromWrite(atomicOperation, entryPointCacheEntry);
    }

    try {
      final Bucket newRightBucket = new Bucket(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries);

      bucketToSplit.shrink(indexToSplit);

      if (splitLeaf) {
        final long rightSiblingPageIndex = bucketToSplit.getRightSibling();

        newRightBucket.setRightSibling(rightSiblingPageIndex);
        newRightBucket.setLeftSibling(pageIndex);

        bucketToSplit.setRightSibling(rightBucketEntry.getPageIndex());

        if (rightSiblingPageIndex >= 0) {
          final OCacheEntry rightSiblingBucketEntry =
              loadPageForWrite(atomicOperation, fileId, rightSiblingPageIndex, false, true);
          final Bucket rightSiblingBucket = new Bucket(rightSiblingBucketEntry);
          try {
            rightSiblingBucket.setLeftSibling(rightBucketEntry.getPageIndex());
          } finally {
            releasePageFromWrite(atomicOperation, rightSiblingBucketEntry);
          }
        }
      }

      long parentIndex = path.get(path.size() - 2);
      OCacheEntry parentCacheEntry =
          loadPageForWrite(atomicOperation, fileId, parentIndex, false, true);
      try {
        Bucket parentBucket = new Bucket(parentCacheEntry);
        int insertionIndex = itemPointers.get(itemPointers.size() - 2);
        while (!parentBucket.addNonLeafEntry(
            insertionIndex,
            (int) pageIndex,
            (int) rightBucketEntry.getPageIndex(),
            EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(separationKey, (Object[]) null),
            true)) {
          final UpdateBucketSearchResult bucketSearchResult =
              splitBucket(
                  parentBucket,
                  parentCacheEntry,
                  path.subList(0, path.size() - 1),
                  itemPointers.subList(0, itemPointers.size() - 1),
                  insertionIndex,
                  atomicOperation);

          parentIndex = bucketSearchResult.getLastPathItem();
          insertionIndex = bucketSearchResult.itemIndex;

          if (parentIndex != parentCacheEntry.getPageIndex()) {
            releasePageFromWrite(atomicOperation, parentCacheEntry);

            parentCacheEntry = loadPageForWrite(atomicOperation, fileId, parentIndex, false, true);
          }

          //noinspection ObjectAllocationInLoop
          parentBucket = new Bucket(parentCacheEntry);
        }

      } finally {
        releasePageFromWrite(atomicOperation, parentCacheEntry);
      }

    } finally {
      releasePageFromWrite(atomicOperation, rightBucketEntry);
    }

    final ArrayList<Long> resultPath = new ArrayList<>(path.subList(0, path.size() - 1));
    final ArrayList<Integer> resultItemPointers =
        new ArrayList<>(itemPointers.subList(0, itemPointers.size() - 1));

    if (keyIndex <= indexToSplit) {
      resultPath.add(pageIndex);
      resultItemPointers.add(keyIndex);

      return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex);
    }

    final int parentIndex = resultItemPointers.size() - 1;
    resultItemPointers.set(parentIndex, resultItemPointers.get(parentIndex) + 1);
    resultPath.add(rightBucketEntry.getPageIndex());

    if (splitLeaf) {
      resultItemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(resultItemPointers, resultPath, keyIndex - indexToSplit);
    }

    resultItemPointers.add(keyIndex - indexToSplit - 1);
    return new UpdateBucketSearchResult(
        resultItemPointers, resultPath, keyIndex - indexToSplit - 1);
  }

  private UpdateBucketSearchResult splitRootBucket(
      final int keyIndex,
      final OCacheEntry bucketEntry,
      Bucket bucketToSplit,
      final boolean splitLeaf,
      final int indexToSplit,
      final EdgeKey separationKey,
      final List<byte[]> rightEntries,
      final OAtomicOperation atomicOperation)
      throws IOException {
    final List<byte[]> leftEntries = new ArrayList<>(indexToSplit);

    for (int i = 0; i < indexToSplit; i++) {
      leftEntries.add(bucketToSplit.getRawEntry(i));
    }

    final OCacheEntry leftBucketEntry;
    final OCacheEntry rightBucketEntry;

    final OCacheEntry entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
    try {
      final EntryPoint entryPoint = new EntryPoint(entryPointCacheEntry);
      int pageSize = entryPoint.getPagesSize();

      final int filledUpTo = (int) getFilledUpTo(atomicOperation, fileId);

      if (pageSize < filledUpTo - 1) {
        pageSize++;
        leftBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false, false);
      } else {
        assert pageSize == filledUpTo - 1;
        leftBucketEntry = addPage(atomicOperation, fileId);
        pageSize = (int) leftBucketEntry.getPageIndex();
      }

      if (pageSize < filledUpTo) {
        pageSize++;
        rightBucketEntry = loadPageForWrite(atomicOperation, fileId, pageSize, false, false);
      } else {
        assert pageSize == filledUpTo;
        rightBucketEntry = addPage(atomicOperation, fileId);
        pageSize = (int) rightBucketEntry.getPageIndex();
      }

      entryPoint.setPagesSize(pageSize);
    } finally {
      releasePageFromWrite(atomicOperation, entryPointCacheEntry);
    }

    try {
      final Bucket newLeftBucket = new Bucket(leftBucketEntry);
      newLeftBucket.init(splitLeaf);
      newLeftBucket.addAll(leftEntries);

      if (splitLeaf) {
        newLeftBucket.setRightSibling(rightBucketEntry.getPageIndex());
      }

    } finally {
      releasePageFromWrite(atomicOperation, leftBucketEntry);
    }

    try {
      final Bucket newRightBucket = new Bucket(rightBucketEntry);
      newRightBucket.init(splitLeaf);
      newRightBucket.addAll(rightEntries);

      if (splitLeaf) {
        newRightBucket.setLeftSibling(leftBucketEntry.getPageIndex());
      }
    } finally {
      releasePageFromWrite(atomicOperation, rightBucketEntry);
    }

    bucketToSplit = new Bucket(bucketEntry);
    bucketToSplit.shrink(0);
    if (splitLeaf) {
      bucketToSplit.switchBucketType();
    }

    bucketToSplit.addNonLeafEntry(
        0,
        (int) leftBucketEntry.getPageIndex(),
        (int) rightBucketEntry.getPageIndex(),
        EdgeKeySerializer.INSTANCE.serializeNativeAsWhole(separationKey, (Object[]) null),
        true);

    final ArrayList<Long> resultPath = new ArrayList<>(8);
    resultPath.add(ROOT_INDEX);

    final ArrayList<Integer> itemPointers = new ArrayList<>(8);

    if (keyIndex <= indexToSplit) {
      itemPointers.add(-1);
      itemPointers.add(keyIndex);

      resultPath.add(leftBucketEntry.getPageIndex());
      return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex);
    }

    resultPath.add(rightBucketEntry.getPageIndex());
    itemPointers.add(0);

    if (splitLeaf) {
      itemPointers.add(keyIndex - indexToSplit);
      return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex - indexToSplit);
    }

    itemPointers.add(keyIndex - indexToSplit - 1);
    return new UpdateBucketSearchResult(itemPointers, resultPath, keyIndex - indexToSplit - 1);
  }

  private void updateSize(final long diffSize, final OAtomicOperation atomicOperation)
      throws IOException {
    final OCacheEntry entryPointCacheEntry =
        loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, false, true);
    try {
      final EntryPoint entryPoint = new EntryPoint(entryPointCacheEntry);
      entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
    } finally {
      releasePageFromWrite(atomicOperation, entryPointCacheEntry);
    }
  }

  private UpdateBucketSearchResult findBucketForUpdate(
      final EdgeKey key, final OAtomicOperation atomicOperation) throws IOException {
    long pageIndex = ROOT_INDEX;

    final ArrayList<Long> path = new ArrayList<>(8);
    final ArrayList<Integer> itemIndexes = new ArrayList<>(8);

    while (true) {
      if (path.size() > MAX_PATH_LENGTH) {
        throw new OStorageException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state. You should rebuild index related to given query.");
      }

      path.add(pageIndex);
      final OCacheEntry bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
      try {
        final Bucket keyBucket = new Bucket(bucketEntry);
        final int index = keyBucket.find(key);

        if (keyBucket.isLeaf()) {
          itemIndexes.add(index);
          return new UpdateBucketSearchResult(itemIndexes, path, index);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
          itemIndexes.add(index + 1);
        } else {
          final int insertionIndex = -index - 1;

          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }

          itemIndexes.add(insertionIndex);
        }
      } finally {
        releasePageFromRead(atomicOperation, bucketEntry);
      }
    }
  }

  private BucketSearchResult findBucket(final EdgeKey key, final OAtomicOperation atomicOperation)
      throws IOException {
    long pageIndex = ROOT_INDEX;

    int depth = 0;
    while (true) {
      depth++;
      if (depth > MAX_PATH_LENGTH) {
        throw new OStorageException(
            "We reached max level of depth of SBTree but still found nothing, seems like tree is in corrupted state. You should rebuild index related to given query.");
      }

      final OCacheEntry bucketEntry = loadPageForRead(atomicOperation, fileId, pageIndex, false);
      try {
        final Bucket keyBucket = new Bucket(bucketEntry);
        final int index = keyBucket.find(key);

        if (keyBucket.isLeaf()) {
          return new BucketSearchResult(index, pageIndex);
        }

        if (index >= 0) {
          pageIndex = keyBucket.getRight(index);
        } else {
          final int insertionIndex = -index - 1;
          if (insertionIndex >= keyBucket.size()) {
            pageIndex = keyBucket.getRight(insertionIndex - 1);
          } else {
            pageIndex = keyBucket.getLeft(insertionIndex);
          }
        }
      } finally {
        releasePageFromRead(atomicOperation, bucketEntry);
      }
    }
  }

  static final class TreeEntry implements Comparable<TreeEntry> {

    protected final int leftChild;
    protected final int rightChild;
    public final EdgeKey key;
    public final int value;

    public TreeEntry(
        final int leftChild, final int rightChild, final EdgeKey key, final int value) {
      this.leftChild = leftChild;
      this.rightChild = rightChild;
      this.key = key;
      this.value = value;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final TreeEntry that = (TreeEntry) o;
      return leftChild == that.leftChild
          && rightChild == that.rightChild
          && Objects.equals(key, that.key)
          && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(leftChild, rightChild, key, value);
    }

    @Override
    public String toString() {
      return "CellBTreeEntry{"
          + "leftChild="
          + leftChild
          + ", rightChild="
          + rightChild
          + ", key="
          + key
          + ", value="
          + value
          + '}';
    }

    @Override
    public int compareTo(final TreeEntry other) {
      return key.compareTo(other.key);
    }
  }

  private static final class BucketSearchResult {

    private final int itemIndex;
    private final long pageIndex;

    private BucketSearchResult(final int itemIndex, final long pageIndex) {
      this.itemIndex = itemIndex;
      this.pageIndex = pageIndex;
    }
  }

  private static final class UpdateBucketSearchResult {

    private final List<Integer> insertionIndexes;
    private final ArrayList<Long> path;
    private final int itemIndex;

    private UpdateBucketSearchResult(
        final List<Integer> insertionIndexes, final ArrayList<Long> path, final int itemIndex) {
      this.insertionIndexes = insertionIndexes;
      this.path = path;
      this.itemIndex = itemIndex;
    }

    private long getLastPathItem() {
      return path.get(path.size() - 1);
    }
  }

  private static final class PagePathItemUnit {

    private final long pageIndex;
    private final int itemIndex;

    private PagePathItemUnit(final long pageIndex, final int itemIndex) {
      this.pageIndex = pageIndex;
      this.itemIndex = itemIndex;
    }
  }
}
