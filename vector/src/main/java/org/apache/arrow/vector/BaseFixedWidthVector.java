/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.arrow.vector;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ipc.message.ArrowFieldNode;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.util.CallBack;
import org.apache.arrow.vector.util.OversizedAllocationException;
import org.apache.arrow.vector.util.TransferPair;

import io.netty.buffer.ArrowBuf;

/**
 * BaseFixedWidthVector provides an abstract interface for
 * implementing vectors of fixed width values. The vectors are nullable
 * implying that zero or more elements in the vector could be NULL.
 */
public abstract class BaseFixedWidthVector extends BaseValueVector
        implements FixedWidthVector, FieldVector, VectorDefinitionSetter {
  private final int typeWidth;

  protected int lastValueCapacity;

  protected final Field field;
  private int allocationMonitor;
  protected ArrowBuf validityBuffer;
  protected ArrowBuf valueBuffer;
  protected int valueCount;

  /**
   * Constructs a new instance.
   *
   * @param name The name of the vector.
   * @param allocator The allocator to use for allocating memory for the vector.
   * @param fieldType The type of the buffer.
   * @param typeWidth The width in bytes of the type.
   */
  public BaseFixedWidthVector(final String name, final BufferAllocator allocator,
                                      FieldType fieldType, final int typeWidth) {
    super(name, allocator);
    this.typeWidth = typeWidth;
    field = new Field(name, fieldType, null);
    valueCount = 0;
    allocationMonitor = 0;
    validityBuffer = allocator.getEmpty();
    valueBuffer = allocator.getEmpty();
    lastValueCapacity = INITIAL_VALUE_ALLOCATION;
  }


  /* TODO:
   * see if getNullCount() can be made faster -- O(1)
   */

  /* TODO:
   * Once the entire hierarchy has been refactored, move common functions
   * like getNullCount(), splitAndTransferValidityBuffer to top level
   * base class BaseValueVector.
   *
   * Along with this, some class members (validityBuffer) can also be
   * abstracted out to top level base class.
   *
   * Right now BaseValueVector is the top level base class for other
   * vector types in ValueVector hierarchy (non-nullable) and those
   * vectors have not yet been refactored/removed so moving things to
   * the top class as of now is not a good idea.
   */

  /**
   * Get the memory address of buffer that manages the validity
   * (NULL or NON-NULL nature) of elements in the vector.
   * @return starting address of the buffer
   */
  @Override
  public long getValidityBufferAddress() {
    return (validityBuffer.memoryAddress());
  }

  /**
   * Get the memory address of buffer that stores the data for elements
   * in the vector.
   * @return starting address of the buffer
   */
  @Override
  public long getDataBufferAddress() {
    return (valueBuffer.memoryAddress());
  }

  /**
   * Get the memory address of buffer that stores the offsets for elements
   * in the vector. This operation is not supported for fixed-width vectors.
   * @return starting address of the buffer
   * @throws UnsupportedOperationException for fixed width vectors
   */
  @Override
  public long getOffsetBufferAddress() {
    throw new UnsupportedOperationException("not supported for fixed-width vectors");
  }

  /**
   * Get buffer that manages the validity (NULL or NON-NULL nature) of
   * elements in the vector. Consider it as a buffer for internal bit vector
   * data structure.
   * @return buffer
   */
  @Override
  public ArrowBuf getValidityBuffer() {
    return validityBuffer;
  }

  /**
   * Get the buffer that stores the data for elements in the vector.
   * @return buffer
   */
  @Override
  public ArrowBuf getDataBuffer() {
    return valueBuffer;
  }

  /**
   * buffer that stores the offsets for elements
   * in the vector. This operation is not supported for fixed-width vectors.
   * @return buffer
   * @throws UnsupportedOperationException for fixed width vectors
   */
  @Override
  public ArrowBuf getOffsetBuffer() {
    throw new UnsupportedOperationException("not supported for fixed-width vectors");
  }

  /**
   * Sets the desired value capacity for the vector. This function doesn't
   * allocate any memory for the vector.
   * @param valueCount desired number of elements in the vector
   */
  @Override
  public void setInitialCapacity(int valueCount) {
    computeAndCheckBufferSize(valueCount);
    lastValueCapacity = valueCount;
  }

  /**
   * Get the current value capacity for the vector
   * @return number of elements that vector can hold.
   */
  @Override
  public int getValueCapacity() {
    return Math.min(getValueBufferValueCapacity(), getValidityBufferValueCapacity());
  }

  private int getValueBufferValueCapacity() {
    return (int) ((valueBuffer.capacity() * 1.0) / typeWidth);
  }

  private int getValidityBufferValueCapacity() {
    return (int) (validityBuffer.capacity() * 8L);
  }

  /**
   * zero out the vector and the data in associated buffers.
   */
  @Override
  public void zeroVector() {
    initValidityBuffer();
    initValueBuffer();
  }

  /* zero out the validity buffer */
  private void initValidityBuffer() {
    validityBuffer.setZero(0, validityBuffer.capacity());
  }

  /* zero out the data buffer */
  private void initValueBuffer() {
    valueBuffer.setZero(0, valueBuffer.capacity());
  }

  /**
   * Reset the vector to initial state. Same as {@link #zeroVector()}.
   * Note that this method doesn't release any memory.
   */
  @Override
  public void reset() {
    valueCount = 0;
    zeroVector();
  }

  /**
   * Close the vector and release the associated buffers.
   */
  @Override
  public void close() {
    clear();
  }

  /**
   * Same as {@link #close()}.
   */
  @Override
  public void clear() {
    valueCount = 0;
    validityBuffer = releaseBuffer(validityBuffer);
    valueBuffer = releaseBuffer(valueBuffer);
  }

  /* used to step down the memory allocation */
  protected void incrementAllocationMonitor() {
    if (allocationMonitor < 0) {
      allocationMonitor = 0;
    }
    allocationMonitor++;
  }

  /* used to step up the memory allocation */
  protected void decrementAllocationMonitor() {
    if (allocationMonitor > 0) {
      allocationMonitor = 0;
    }
    allocationMonitor--;
  }

  /**
   * Same as {@link #allocateNewSafe()}.
   */
  @Override
  public void allocateNew() {
    if (!allocateNewSafe()) {
      throw new OutOfMemoryException("Failure while allocating memory.");
    }
  }

  /**
   * Allocate memory for the vector. We internally use a default value count
   * of 4096 to allocate memory for at least these many elements in the
   * vector. See {@link #allocateNew(int)} for allocating memory for specific
   * number of elements in the vector.
   *
   * @return false if memory allocation fails, true otherwise.
   */
  @Override
  public boolean allocateNewSafe() {
    computeAndCheckBufferSize(lastValueCapacity);

    /* we are doing a new allocation -- release the current buffers */
    clear();

    try {
      allocateBytes(lastValueCapacity);
    } catch (Exception e) {
      clear();
      return false;
    }

    return true;
  }

  /**
   * Allocate memory for the vector to support storing at least the provided number of
   * elements in the vector. This method must be called prior to using the ValueVector.
   *
   * @param valueCount the desired number of elements in the vector
   * @throws org.apache.arrow.memory.OutOfMemoryException on error
   */
  public void allocateNew(int valueCount) {
    computeAndCheckBufferSize(valueCount);

    /* we are doing a new allocation -- release the current buffers */
    clear();

    try {
      allocateBytes(valueCount);
    } catch (Exception e) {
      clear();
      throw e;
    }
  }

  /*
   * Compute the buffer size required for 'valueCount', and check if it's within bounds.
   */
  private long computeAndCheckBufferSize(int valueCount) {
    final long size = computeCombinedBufferSize(valueCount, typeWidth);
    if (size > MAX_ALLOCATION_SIZE) {
      throw new OversizedAllocationException("Memory required for vector capacity " +
          valueCount +
          " is (" + size + "), which is more than max allowed (" + MAX_ALLOCATION_SIZE + ")");
    }
    return size;
  }

  /**
   * Actual memory allocation is done by this function. All the calculations
   * and knowledge about what size to allocate is upto the callers of this
   * method.
   * Callers appropriately handle errors if memory allocation fails here.
   * Callers should also take care of determining that desired size is
   * within the bounds of max allocation allowed and any other error
   * conditions.
   */
  private void allocateBytes(int valueCount) {
    DataAndValidityBuffers buffers = allocFixedDataAndValidityBufs(valueCount, typeWidth);
    valueBuffer = buffers.getDataBuf();
    validityBuffer = buffers.getValidityBuf();
    zeroVector();

    lastValueCapacity = getValueCapacity();
  }

  /**
   * During splitAndTransfer, if we splitting from a random position within a byte,
   * we can't just slice the source buffer so we have to explicitly allocate the
   * validityBuffer of the target vector. This is unlike the databuffer which we can
   * always slice for the target vector.
   */
  private void allocateValidityBuffer(final int validityBufferSize) {
    validityBuffer = allocator.buffer(validityBufferSize);
    validityBuffer.readerIndex(0);
  }

  /**
   * Get the potential buffer size for a particular number of records.
   * @param count desired number of elements in the vector
   * @return estimated size of underlying buffers if the vector holds
   *         a given number of elements
   */
  @Override
  public int getBufferSizeFor(final int count) {
    if (count == 0) {
      return 0;
    }
    return (count * typeWidth) + getValidityBufferSizeFromCount(count);
  }

  /**
   * Get the size (number of bytes) of underlying buffers used by this
   * vector.
   * @return size of underlying buffers.
   */
  @Override
  public int getBufferSize() {
    if (valueCount == 0) {
      return 0;
    }
    return (valueCount * typeWidth) + getValidityBufferSizeFromCount(valueCount);
  }

  /**
   * Get information about how this field is materialized.
   * @return the field corresponding to this vector
   */
  @Override
  public Field getField() {
    return field;
  }

  /**
   * Return the underlying buffers associated with this vector. Note that this doesn't
   * impact the reference counts for this buffer so it only should be used for in-context
   * access. Also note that this buffer changes regularly thus
   * external classes shouldn't hold a reference to it (unless they change it).
   *
   * @param clear Whether to clear vector before returning; the buffers will still be refcounted
   *              but the returned array will be the only reference to them
   * @return The underlying {@link io.netty.buffer.ArrowBuf buffers} that is used by this
   *         vector instance.
   */
  @Override
  public ArrowBuf[] getBuffers(boolean clear) {
    final ArrowBuf[] buffers;
    setReaderAndWriterIndex();
    if (getBufferSize() == 0) {
      buffers = new ArrowBuf[0];
    } else {
      buffers = new ArrowBuf[2];
      buffers[0] = validityBuffer;
      buffers[1] = valueBuffer;
    }
    if (clear) {
      for (final ArrowBuf buffer : buffers) {
        buffer.getReferenceManager().retain(1);
      }
      clear();
    }
    return buffers;
  }

  /**
   * Resize the vector to increase the capacity. The internal behavior is to
   * double the current value capacity.
   */
  @Override
  public void reAlloc() {
    int targetValueCount = getValueCapacity() * 2;
    if (targetValueCount == 0) {
      if (lastValueCapacity > 0) {
        targetValueCount = lastValueCapacity;
      } else {
        targetValueCount = INITIAL_VALUE_ALLOCATION * 2;
      }
    }
    computeAndCheckBufferSize(targetValueCount);

    DataAndValidityBuffers buffers = allocFixedDataAndValidityBufs(targetValueCount, typeWidth);
    final ArrowBuf newValueBuffer = buffers.getDataBuf();
    newValueBuffer.setBytes(0, valueBuffer, 0, valueBuffer.capacity());
    newValueBuffer.setZero(valueBuffer.capacity(), newValueBuffer.capacity() - valueBuffer.capacity());
    valueBuffer.getReferenceManager().release();
    valueBuffer = newValueBuffer;

    final ArrowBuf newValidityBuffer = buffers.getValidityBuf();
    newValidityBuffer.setBytes(0, validityBuffer, 0, validityBuffer.capacity());
    newValidityBuffer.setZero(validityBuffer.capacity(), newValidityBuffer.capacity() - validityBuffer.capacity());
    validityBuffer.getReferenceManager().release();
    validityBuffer = newValidityBuffer;

    lastValueCapacity = getValueCapacity();
  }

  @Override
  @Deprecated
  public List<BufferBacked> getFieldInnerVectors() {
    throw new UnsupportedOperationException("There are no inner vectors. Use getFieldBuffers");
  }

  /**
   * Initialize the children in schema for this Field. This operation is a
   * NO-OP for scalar types since they don't have any children.
   * @param children the schema
   * @throws IllegalArgumentException if children is a non-empty list for scalar types.
   */
  @Override
  public void initializeChildrenFromFields(List<Field> children) {
    if (!children.isEmpty()) {
      throw new IllegalArgumentException("primitive type vector can not have children");
    }
  }

  /**
   * Get the inner child vectors.
   * @return list of child vectors for complex types, empty list for scalar vector types
   */
  @Override
  public List<FieldVector> getChildrenFromFields() {
    return Collections.emptyList();
  }

  /**
   * Load the buffers of this vector with provided source buffers.
   * The caller manages the source buffers and populates them before invoking
   * this method.
   * @param fieldNode  the fieldNode indicating the value count
   * @param ownBuffers the buffers for this Field (own buffers only, children not included)
   */
  @Override
  public void loadFieldBuffers(ArrowFieldNode fieldNode, List<ArrowBuf> ownBuffers) {
    if (ownBuffers.size() != 2) {
      throw new IllegalArgumentException("Illegal buffer count, expected " + 2 + ", got: " + ownBuffers.size());
    }

    ArrowBuf bitBuffer = ownBuffers.get(0);
    ArrowBuf dataBuffer = ownBuffers.get(1);

    validityBuffer.getReferenceManager().release();
    validityBuffer = BitVectorHelper.loadValidityBuffer(fieldNode, bitBuffer, allocator);
    valueBuffer.getReferenceManager().release();
    valueBuffer = dataBuffer.getReferenceManager().retain(dataBuffer, allocator);

    valueCount = fieldNode.getLength();
  }

  /**
   * Get the buffers belonging to this vector
   * @return the inner buffers.
   */
  public List<ArrowBuf> getFieldBuffers() {
    List<ArrowBuf> result = new ArrayList<>(2);
    setReaderAndWriterIndex();
    result.add(validityBuffer);
    result.add(valueBuffer);

    return result;
  }

  /**
   * Set the reader and writer indexes for the inner buffers.
   */
  private void setReaderAndWriterIndex() {
    validityBuffer.readerIndex(0);
    valueBuffer.readerIndex(0);
    if (valueCount == 0) {
      validityBuffer.writerIndex(0);
      valueBuffer.writerIndex(0);
    } else {
      validityBuffer.writerIndex(getValidityBufferSizeFromCount(valueCount));
      if (typeWidth == 0) {
        /* specialized handling for BitVector */
        valueBuffer.writerIndex(getValidityBufferSizeFromCount(valueCount));
      } else {
        valueBuffer.writerIndex(valueCount * typeWidth);
      }
    }
  }

  /**
   * Construct a transfer pair of this vector and another vector of same type.
   * @param ref name of the target vector
   * @param allocator allocator for the target vector
   * @param callBack not used
   * @return TransferPair
   */
  @Override
  public TransferPair getTransferPair(String ref, BufferAllocator allocator, CallBack callBack) {
    return getTransferPair(ref, allocator);
  }

  /**
   * Construct a transfer pair of this vector and another vector of same type.
   * @param allocator allocator for the target vector
   * @return TransferPair
   */
  @Override
  public TransferPair getTransferPair(BufferAllocator allocator) {
    return getTransferPair(name, allocator);
  }

  /**
   * Construct a transfer pair of this vector and another vector of same type.
   * @param ref name of the target vector
   * @param allocator allocator for the target vector
   * @return TransferPair
   */
  public abstract TransferPair getTransferPair(String ref, BufferAllocator allocator);

  /**
   * Transfer this vector'data to another vector. The memory associated
   * with this vector is transferred to the allocator of target vector
   * for accounting and management purposes.
   * @param target destination vector for transfer
   */
  public void transferTo(BaseFixedWidthVector target) {
    compareTypes(target, "transferTo");
    target.clear();
    target.validityBuffer = transferBuffer(validityBuffer, target.allocator);
    target.valueBuffer = transferBuffer(valueBuffer, target.allocator);
    target.valueCount = valueCount;
    clear();
  }

  /**
   * Slice this vector at desired index and length and transfer the
   * corresponding data to the target vector.
   * @param startIndex start position of the split in source vector.
   * @param length length of the split.
   * @param target destination vector
   */
  public void splitAndTransferTo(int startIndex, int length,
                                 BaseFixedWidthVector target) {
    compareTypes(target, "splitAndTransferTo");
    target.clear();
    splitAndTransferValidityBuffer(startIndex, length, target);
    splitAndTransferValueBuffer(startIndex, length, target);
    target.setValueCount(length);
  }

  /**
   * Data buffer can always be split and transferred using slicing.
   */
  private void splitAndTransferValueBuffer(int startIndex, int length,
                                           BaseFixedWidthVector target) {
    final int startPoint = startIndex * typeWidth;
    final int sliceLength = length * typeWidth;
    final ArrowBuf slicedBuffer = valueBuffer.slice(startPoint, sliceLength);
    target.valueBuffer = transferBuffer(slicedBuffer, target.allocator);
  }

  /**
   * Validity buffer has multiple cases of split and transfer depending on
   * the starting position of the source index.
   */
  private void splitAndTransferValidityBuffer(int startIndex, int length,
                                              BaseFixedWidthVector target) {
    assert startIndex + length <= valueCount;
    int firstByteSource = BitVectorHelper.byteIndex(startIndex);
    int lastByteSource = BitVectorHelper.byteIndex(valueCount - 1);
    int byteSizeTarget = getValidityBufferSizeFromCount(length);
    int offset = startIndex % 8;

    if (length > 0) {
      if (offset == 0) {
        /* slice */
        if (target.validityBuffer != null) {
          target.validityBuffer.getReferenceManager().release();
        }
        target.validityBuffer = validityBuffer.slice(firstByteSource, byteSizeTarget);
        target.validityBuffer.getReferenceManager().retain(1);
      } else {
        /* Copy data
         * When the first bit starts from the middle of a byte (offset != 0),
         * copy data from src BitVector.
         * Each byte in the target is composed by a part in i-th byte,
         * another part in (i+1)-th byte.
         */
        target.allocateValidityBuffer(byteSizeTarget);

        for (int i = 0; i < byteSizeTarget - 1; i++) {
          byte b1 = BitVectorHelper.getBitsFromCurrentByte(this.validityBuffer,
                  firstByteSource + i, offset);
          byte b2 = BitVectorHelper.getBitsFromNextByte(this.validityBuffer,
                  firstByteSource + i + 1, offset);

          target.validityBuffer.setByte(i, (b1 + b2));
        }

        /* Copying the last piece is done in the following manner:
         * if the source vector has 1 or more bytes remaining, we copy
         * the last piece as a byte formed by shifting data
         * from the current byte and the next byte.
         *
         * if the source vector has no more bytes remaining
         * (we are at the last byte), we copy the last piece as a byte
         * by shifting data from the current byte.
         */
        if ((firstByteSource + byteSizeTarget - 1) < lastByteSource) {
          byte b1 = BitVectorHelper.getBitsFromCurrentByte(this.validityBuffer,
                  firstByteSource + byteSizeTarget - 1, offset);
          byte b2 = BitVectorHelper.getBitsFromNextByte(this.validityBuffer,
                  firstByteSource + byteSizeTarget, offset);

          target.validityBuffer.setByte(byteSizeTarget - 1, b1 + b2);
        } else {
          byte b1 = BitVectorHelper.getBitsFromCurrentByte(this.validityBuffer,
                  firstByteSource + byteSizeTarget - 1, offset);
          target.validityBuffer.setByte(byteSizeTarget - 1, b1);
        }
      }
    }
  }


  /*----------------------------------------------------------------*
   |                                                                |
   |          common getters and setters                            |
   |                                                                |
   *----------------------------------------------------------------*/


  /**
   * Get the number of elements that are null in the vector.
   *
   * @return the number of null elements.
   */
  @Override
  public int getNullCount() {
    return BitVectorHelper.getNullCount(validityBuffer, valueCount);
  }

  /**
   * Get the value count of vector. This will always be zero unless
   * {@link #setValueCount(int)} has been called prior to calling this.
   *
   * @return valueCount for the vector
   */
  @Override
  public int getValueCount() {
    return valueCount;
  }

  /**
   * Set value count for the vector.
   *
   * @param valueCount  value count to set
   */
  @Override
  public void setValueCount(int valueCount) {
    this.valueCount = valueCount;
    final int currentValueCapacity = getValueCapacity();
    while (valueCount > getValueCapacity()) {
      reAlloc();
    }
    /*
     * We are trying to understand the pattern of memory allocation.
     * If initially, the user did vector.allocateNew(), we would have
     * allocated memory of default size (4096 * type width).
     * Later on user invokes setValueCount(count).
     *
     * If the existing value capacity is twice as large as the
     * valueCount, we know that we over-provisioned memory in the
     * first place when default memory allocation was done because user
     * really needs a much less value count in the vector.
     *
     * We record this by bumping up the allocationMonitor. If this pattern
     * happens for certain number of times and allocationMonitor
     * reaches the threshold (internal hardcoded) value, subsequent
     * call to allocateNew() will take care of stepping down the
     * default memory allocation size.
     *
     * Another case would be under-provisioning the initial memory and
     * thus going through a lot of realloc(). Here the goal is to
     * see if we can minimize the number of reallocations. Again the
     * state is recorded in allocationMonitor by decrementing it
     * (negative value). If a threshold is hit, realloc will try to
     * allocate more memory in order to possibly avoid a future realloc.
     * This case is also applicable to setSafe() methods which can trigger
     * a realloc() and thus we record the state there as well.
     */
    if (valueCount > 0) {
      if (currentValueCapacity >= (valueCount * 2)) {
        incrementAllocationMonitor();
      } else if (currentValueCapacity <= (valueCount / 2)) {
        decrementAllocationMonitor();
      }
    }
    setReaderAndWriterIndex();
  }

  /**
   * Check if the given index is within the current value capacity
   * of the vector.
   *
   * @param index  position to check
   * @return true if index is within the current value capacity
   */
  public boolean isSafe(int index) {
    return index < getValueCapacity();
  }

  /**
   * Check if element at given index is null.
   *
   * @param index  position of element
   * @return true if element at given index is null, false otherwise
   */
  @Override
  public boolean isNull(int index) {
    return (isSet(index) == 0);
  }

  /**
   * Same as {@link #isNull(int)}.
   *
   * @param index  position of element
   * @return 1 if element at given index is not null, 0 otherwise
   */
  public int isSet(int index) {
    final int byteIndex = index >> 3;
    final byte b = validityBuffer.getByte(byteIndex);
    final int bitIndex = index & 7;
    return (b >> bitIndex) & 0x01;
  }

  /**
   * Mark the particular position in the vector as non-null.
   *
   * @param index position of the element.
   */
  @Override
  public void setIndexDefined(int index) {
    handleSafe(index);
    BitVectorHelper.setValidityBitToOne(validityBuffer, index);
  }

  public void set(int index, byte[] value, int start, int length) {
    throw new UnsupportedOperationException();
  }

  public void setSafe(int index, byte[] value, int start, int length) {
    throw new UnsupportedOperationException();
  }

  public void set(int index, ByteBuffer value, int start, int length) {
    throw new UnsupportedOperationException();
  }

  public void setSafe(int index, ByteBuffer value, int start, int length) {
    throw new UnsupportedOperationException();
  }


  /*----------------------------------------------------------------*
   |                                                                |
   |                helper methods for setters                      |
   |                                                                |
   *----------------------------------------------------------------*/


  protected void handleSafe(int index) {
    while (index >= getValueCapacity()) {
      decrementAllocationMonitor();
      reAlloc();
    }
  }
}
