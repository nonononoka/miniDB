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
const val INTERNAL_NODE_MAX_CELLS = 3

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

fun internalNodeFindChild(node: ByteBuffer, key: Int): Int {
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
    return minIndex
}

// internal nodeのkeyを見て，そのchild nodeを辿っていく
// そのchild nodeがinternal nodeだったら同じことをする
// child nodeがleaf nodeだったら，leafを見ていく
fun internalNodeFind(table: Table, pageNum: Int, key: Int): Cursor {
    val node = getPage(table.pager, pageNum)
    val childIndex = internalNodeFindChild(node, key)
    val childPageNum = internalNodeChild(node, childIndex).getInt()
    val child = getPage(table.pager, childPageNum)
    when (getNodeType(child)) {
        TreeNodeType.NODE_INTERNAL -> {
            return internalNodeFind(table, childPageNum, key)
        }

        TreeNodeType.NODE_LEAF -> {
            return leafNodeFind(table, childPageNum, key)
        }
    }
}

fun updateInternalNodeKey(byteBuffer: ByteBuffer, oldKey: Int, newKey: Int) {
    val oldChildIndex = internalNodeFindChild(byteBuffer, oldKey)
    internalNodeKey(byteBuffer, oldChildIndex).putInt(newKey)
}

fun internalNodeInsert(table: Table, parentPageNum: Int, childPageNum: Int) {
    // parentに，新しいchildへのポインタを足す
    val parent = getPage(table.pager, parentPageNum)
    val child = getPage(table.pager, childPageNum)
    val childMaxKey = getNodeMaxKey(child)
    val index = internalNodeFindChild(parent, childMaxKey)

    val originalNumKeys = internalNodeNumKeys(parent).getInt()
    internalNodeNumKeys(parent).putInt(originalNumKeys + 1)

    // leafをsplitした結果，親ノードのinternal nodeのsplitが発生したとき
    if (originalNumKeys >= INTERNAL_NODE_MAX_CELLS) {
        println("Need to implement splitting internal node")
    }

    // 今のright Childとright Child Page Num
    val rightChildPageNum = internalNodeRightChild(parent).getInt()
    val rightChild = getPage(table.pager, rightChildPageNum)
    // Replace right child
    if (childMaxKey > getNodeMaxKey(rightChild)) {
        // 今のrightChild
        internalNodeChild(parent, originalNumKeys).putInt(rightChildPageNum)
        internalNodeKey(parent, originalNumKeys).putInt(getNodeMaxKey(rightChild))
        internalNodeRightChild(parent).putInt(childPageNum)
    } else {
        // 後ろのやつをずらす
        for (i in originalNumKeys downTo index + 1) {
            val tmp = ByteArray(INTERNAL_NODE_CELL_SIZE)
            internalNodeCell(parent, i - 1).get(tmp)
            internalNodeCell(parent, i).put(tmp)
        }
        internalNodeChild(parent, index).putInt(childPageNum)
        internalNodeKey(parent, index).putInt(childMaxKey)
    }
}