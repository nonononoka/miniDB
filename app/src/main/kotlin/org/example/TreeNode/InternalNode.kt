package org.example

import java.nio.ByteBuffer
import kotlin.system.exitProcess

// Internal Node Header Layout
const val INTERNAL_NODE_NUM_KEYS_SIZE = 4
const val INTERNAL_NODE_NUM_KEYS_OFFSET = COMMON_NODE_HEADER_SIZE
const val INTERNAL_NODE_RIGHT_CHILD_SIZE = 4
const val INTERNAL_NODE_RIGHT_CHILD_OFFSET = INTERNAL_NODE_NUM_KEYS_OFFSET + INTERNAL_NODE_NUM_KEYS_SIZE;
const val INTERNAL_NODE_HEADER_SIZE =
    COMMON_NODE_HEADER_SIZE + INTERNAL_NODE_NUM_KEYS_SIZE + INTERNAL_NODE_RIGHT_CHILD_SIZE

// Internal Node Body Layout
const val INTERNAL_NODE_KEY_SIZE = 4
const val INTERNAL_NODE_CHILD_SIZE = 4
// 1つのcellは [child(4B) | key(4B)]
const val INTERNAL_NODE_CELL_SIZE = INTERNAL_NODE_CHILD_SIZE + INTERNAL_NODE_KEY_SIZE

// utility関数
fun internalNodeNumKeys(byteBuffer: ByteBuffer): ByteBuffer {
    return byteBuffer.position(INTERNAL_NODE_NUM_KEYS_OFFSET)
}

fun internalNodeRightChild(byteBuffer: ByteBuffer): ByteBuffer {
    return byteBuffer.position(INTERNAL_NODE_RIGHT_CHILD_OFFSET)
}

fun internalNodeCell(byteBuffer: ByteBuffer, cellNum: Int): ByteBuffer {
    return byteBuffer.position(INTERNAL_NODE_HEADER_SIZE + cellNum * INTERNAL_NODE_CELL_SIZE)
}

fun internalNodeChild(byteBuffer: ByteBuffer, childNum: Int): ByteBuffer {
    // keyの数
    val numKeys = internalNodeNumKeys(byteBuffer).getInt()
    // childNumが0-indexだったとして，numKeys以下まで
    // numKeysの個数+1がchildの個数だから
    if (childNum > numKeys) {
        println("Tried to access child_num=$childNum > numKeys=$numKeys")
        exitProcess(1)
    } else if (childNum == numKeys) {
        return internalNodeRightChild(byteBuffer)
    } else {
        return internalNodeCell(byteBuffer, childNum)
    }
}

fun internalNodeKey(byteBuffer: ByteBuffer, keyNum: Int): ByteBuffer {
    // keyはcellの中でchildの後ろに置かれている
    return byteBuffer.position(internalNodeCell(byteBuffer, keyNum).position() + INTERNAL_NODE_CHILD_SIZE)
}

// bplustreeにおいて，一番右が一番大きいkey
fun getNodeMaxKey(byteBuffer: ByteBuffer): Int {
    val key = internalNodeNumKeys(byteBuffer).getInt() - 1
    when (getNodeType(byteBuffer)) {
        TreeNodeType.NODE_INTERNAL -> return internalNodeKey(
            byteBuffer,
            internalNodeNumKeys(byteBuffer).getInt() - 1
        ).getInt()

        TreeNodeType.NODE_LEAF -> return leafNodeKey(byteBuffer, leafNodeNumCells(byteBuffer).getInt() - 1).getInt()
    }
}

fun isNodeRoot(byteBuffer: ByteBuffer): Boolean {
    val isNodeRoot = byteBuffer.position(IS_ROOT_OFFSET).get()
    return isNodeRoot == 1.toByte()
}

fun setNodeRoot(byteBuffer: ByteBuffer, isRoot: Boolean) {
    if (isRoot) {
        byteBuffer.put(IS_ROOT_OFFSET, 1.toByte())
    } else {
        byteBuffer.put(IS_ROOT_OFFSET, 0.toByte())
    }
}

fun initializeInternalNode(byteBuffer: ByteBuffer) {
    setNodeType(byteBuffer, TreeNodeType.NODE_INTERNAL)
    setNodeRoot(byteBuffer, false)
    internalNodeNumKeys(byteBuffer).putInt(0)
}

// recursively search the node
fun internalNodeFind(table: Table, pageNum: Int, key: Int): Cursor {
    val node = getPage(table.pager, pageNum)
    val numKeys = internalNodeNumKeys(node).getInt()

    // Binary search to find index of child to search
    var minIndex = 0
    var maxIndex = numKeys

    while (minIndex != maxIndex) {
        val index = (minIndex + maxIndex) / 2
        val keyToRight = internalNodeKey(node, index).getInt()
        if (keyToRight >= key) {
            maxIndex = index
        } else {
            minIndex = index + 1
        }
    }

    val childNum = internalNodeChild(node, minIndex).getInt()
    val child = getPage(table.pager, childNum)
    when (getNodeType(child)) {
        TreeNodeType.NODE_INTERNAL -> {
            return internalNodeFind(table, childNum, key)
        }

        TreeNodeType.NODE_LEAF -> {
            return leafNodeFind(table, childNum, key)
        }
    }
}