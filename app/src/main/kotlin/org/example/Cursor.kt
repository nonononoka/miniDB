package org.example

import java.nio.ByteBuffer
import kotlin.system.exitProcess

// どのpageのどのcellかを表す
class Cursor(val table: Table, var pageNum: Int, var cellNum: Int, var endOfTable: Boolean) {
    // cellを一つ進める
    fun advance() {
        val pageNum = this.pageNum
        val node = getPage(this.table.pager, pageNum)
        this.cellNum += 1
        if (this.cellNum >= leafNodeNumCells(node).getInt()) {
            this.endOfTable = true
        }
    }

    // どのページの何バイト目か
    fun value(): ByteBuffer {
        // 何ページ目か
        val pageNum = this.pageNum
        val page = getPage(table.pager, pageNum)
        return leafNodeValue(page, this.cellNum)
    }
}

fun tableStart(table: Table): Cursor {
    val cursor = Cursor(table, table.rootPageNum, 0, false)
    // rootNodeは，木のrootへのポインタ
    val rootNode = getPage(table.pager, table.rootPageNum)
    val numCells = leafNodeNumCells(rootNode).getInt()
    // numCellsが0ではなかったら，まだendOfTableではない．
    cursor.endOfTable = numCells == 0
    return cursor
}

// return the position of the given key
// If the key is not present, return the position
// where it should be inserted
fun tableFind(table: Table, key: Int): Cursor {
    val rootPageNum = table.rootPageNum
    val rootNode = getPage(table.pager, rootPageNum)

    if (getNodeType(rootNode) == TreeNodeType.NODE_LEAF) {
        return leafNodeFind(table, rootPageNum, key)
    } else {
        return internalNodeFind(table, rootPageNum, key)
    }
}

