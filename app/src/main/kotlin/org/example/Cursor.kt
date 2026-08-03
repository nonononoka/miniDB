package org.example

import java.nio.ByteBuffer

class Cursor(val table: Table, var rowNum: Int, var endOfTable: Boolean) {
    fun advance() {
        rowNum += 1
        if (rowNum >= table.numRows) {
            endOfTable = true
        }
    }

    // どのページの何バイト目か
    fun value(): Pair<ByteBuffer, Int> {
        // 何ページ目か
        val pageNum = rowNum / ROWS_PER_PAGE
        val page = getPage(table.pager, pageNum)
        val rowOffset = rowNum % ROWS_PER_PAGE
        val byteOffset = rowOffset * ROWS_PER_PAGE
        return Pair(page, byteOffset)
    }
}

fun tableStart(table: Table): Cursor {
    val cursor = Cursor(table, 0, table.numRows == 0)
    return cursor
}

fun tableEnd(table: Table): Cursor {
    val cursor = Cursor(table, table.numRows, true)
    return cursor
}
