package org.example

import java.nio.ByteBuffer

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

fun tableEnd(table: Table): Cursor {
    val rootNode = getPage(table.pager, table.rootPageNum)
    val cursor = Cursor(table, table.rootPageNum, leafNodeNumCells(rootNode).getInt(), true)
    return cursor
}
