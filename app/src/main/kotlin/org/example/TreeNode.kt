package org.example

import java.nio.ByteBuffer
import kotlin.system.exitProcess

// それぞれのnodeが1ページに相当する
enum class TreeNodeType {
    NODE_LEAF,
    NODE_INTERNAL,
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
    setNodeType(byteBuffer, TreeNodeType.NODE_LEAF)
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

    // これは新しく足すのではなくて，すでにある位置にぶっこむ場合
    // このとき，後ろのnodeを全部ずらす必要がある
    if (cursor.cellNum < numCells) {
        // cursor.cellNum番目から，numcells-1番目を全部LEAF_NODE_CELL_SIZEだけ
        // 後ろにずらす
        // 前からやっていくとデータ壊すので，後ろからやっていく
        val tmp = ByteArray(LEAF_NODE_CELL_SIZE)
        for (i in (numCells - 1) downTo cursor.cellNum) {
            leafNodeCell(node, i).get(tmp)
            leafNodeCell(node, i + 1).put(tmp)
        }
    }

    val newNumCell = leafNodeNumCells(node).getInt() + 1
    leafNodeNumCells(node).putInt(newNumCell)
    leafNodeKey(node, cursor.cellNum).putInt(key)
    serializeRow(value, leafNodeValue(node, cursor.cellNum))
}

// 該当pageの中に，指定されたkeyがあるかを探す
// あったらそこを指すcursorを返して
// なかったら入るはずのcursorを返す
fun leafNodeFind(table: Table, pageNum: Int, key: Int): Cursor {
    val node = getPage(table.pager, pageNum)
    val numCells = leafNodeNumCells(node).getInt()
    val cursor = Cursor(table, pageNum, 0, false)
    var minIndex = 0
    var onePastMaxIndex = numCells

    while (onePastMaxIndex != minIndex) {
        val index = (minIndex + onePastMaxIndex) / 2
        // index番目のkeyをとってくる
        val keyAtIndex = leafNodeKey(node, index).getInt()
        if (key == keyAtIndex) {
            cursor.cellNum = index
            if (index == numCells - 1) {
                cursor.endOfTable = true
            }
            return cursor
        }
        if (key < keyAtIndex) {
            onePastMaxIndex = index
        } else {
            minIndex = index + 1
        }
    }

    cursor.cellNum = minIndex
    if (minIndex == numCells - 1) {
        cursor.endOfTable = true
    }
    return cursor
}

fun getNodeType(node: ByteBuffer): TreeNodeType {
    val value = node.get(NODE_TYPE_OFFSET)
    return when (value) {
        0.toByte() -> TreeNodeType.NODE_LEAF
        1.toByte() -> TreeNodeType.NODE_INTERNAL
        else -> throw IllegalArgumentException("不正なノードタイプです: $value")
    }
}

fun setNodeType(node: ByteBuffer, nodeType: TreeNodeType) {
    node.put(NODE_TYPE_OFFSET, nodeType.ordinal.toByte())
}