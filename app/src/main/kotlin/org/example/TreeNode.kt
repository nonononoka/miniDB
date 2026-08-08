package org.example

import com.google.common.collect.Table
import java.nio.ByteBuffer
import kotlin.system.exitProcess

// それぞれのnodeが1ページに相当する
enum class TreeNode {
    NODE_INTERNAL,
    NODE_LEAF
}

// Common Node Header Layout
const val NODE_TYPE_SIZE = 1
const val NODE_TYPE_OFFSET = 0
const val IS_ROOT_SIZE = 1
const val IS_ROOT_OFFSET = NODE_TYPE_SIZE
const val PARENT_POINTER_SIZE = 4
const val PARENT_POINTER_OFFSET = IS_ROOT_OFFSET + IS_ROOT_SIZE
const val COMMON_NODE_HEADER_SIZE = NODE_TYPE_SIZE + IS_ROOT_SIZE + PARENT_POINTER_SIZE

// Leaf Node Header Layout
const val LEAF_NODE_NUM_CELLS_SIZE = 4
const val LEAF_NODE_NUM_CELLS_OFFSET = COMMON_NODE_HEADER_SIZE
const val LEAF_NODE_HEADER_SIZE = COMMON_NODE_HEADER_SIZE + LEAF_NODE_NUM_CELLS_SIZE

// Leaf Node Body Layout
const val LEAF_NODE_KEY_SIZE = 4
const val LEAF_NODE_KEY_OFFSET = 0
const val LEAF_NODE_VALUE_SIZE = ROW_SIZE
const val LEAF_NODE_VALUE_OFFSET = LEAF_NODE_KEY_OFFSET + LEAF_NODE_KEY_SIZE
const val LEAF_NODE_CELL_SIZE = LEAF_NODE_KEY_SIZE + LEAF_NODE_VALUE_SIZE
const val LEAF_NODE_SPACE_FOR_CELLS = PAGE_SIZE - LEAF_NODE_HEADER_SIZE
const val LEAF_NODE_MAX_CELLS = LEAF_NODE_SPACE_FOR_CELLS / LEAF_NODE_CELL_SIZE

// utility関数
fun leafNodeNumCells(byteBuffer: ByteBuffer): ByteBuffer {
    return byteBuffer.position(LEAF_NODE_NUM_CELLS_OFFSET)
}

fun leafNodeCell(byteBuffer: ByteBuffer, cellNum: Int): ByteBuffer {
    return byteBuffer.position(LEAF_NODE_HEADER_SIZE + cellNum * LEAF_NODE_CELL_SIZE)
}

fun leafNodeKey(byteBuffer: ByteBuffer, cellNum: Int): ByteBuffer {
    return leafNodeCell(byteBuffer, cellNum)
}

fun leafNodeValue(byteBuffer: ByteBuffer, cellNum: Int): ByteBuffer {
    return byteBuffer.position(leafNodeCell(byteBuffer, cellNum).position() + LEAF_NODE_KEY_SIZE)
}

fun initializeLeafNode(byteBuffer: ByteBuffer) {
    // まだ1つもcellが入っていない
    leafNodeNumCells(byteBuffer).putInt(0)
}

// 実際にinsertする関数
fun leafNodeInsert(cursor: Cursor, key: Int, value: Row) {
    val node = getPage(cursor.table.pager, cursor.pageNum)
    val numCells = leafNodeNumCells(node).getInt()
    if (numCells >= LEAF_NODE_MAX_CELLS) {
        println("Need to implement splitting a leaf node")
        return
    }

    val newNumCell = leafNodeNumCells(node).getInt() + 1
    leafNodeNumCells(node).putInt(newNumCell)
    leafNodeKey(node, cursor.cellNum).putInt(key)
    serializeRow(value, leafNodeValue(node, cursor.cellNum))
}