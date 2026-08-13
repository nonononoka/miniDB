package org.example

import com.sun.org.apache.xalan.internal.xsltc.compiler.util.NodeType
import java.nio.ByteBuffer
import kotlin.system.exitProcess

// どのpageのどのcellかを表す
class Cursor(val table: Table, var pageNum: Int, var cellNum: Int, var endOfTable: Boolean) {
    // cellを一つ進める
    fun advance() {
        val pageNum = this.pageNum
        val node = getPage(this.table.pager, pageNum)
        this.cellNum += 1
        // 次のpageに行くとき
        if (this.cellNum >= leafNodeNumCells(node).getInt()) {
            val nextPageNum = leafNodeNextLeaf(node).getInt()
            if (nextPageNum == 0) {
                // this was the rightmost leaf
                endOfTable = true
            } else {
                this.pageNum = nextPageNum
                cellNum = 0
            }
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
    // 0はminimum possible key．
    // もし最小値が0以上だとしても，とりあえず0をkeyにしておけば
    // 一番小さいところを指してくれる
    val cursor = tableFind(table, 0)
    // 一番小さいところは，leafNodeを指す
    val node = getPage(table.pager, cursor.pageNum)
    val numCells = leafNodeNumCells(node).getInt()
    cursor.endOfTable = numCells == 0
    return cursor
}

// return the position of the given key
// If the key is not present, return the position
// where it should be inserted
// これは絶対にleaf nodeを返す
fun tableFind(table: Table, key: Int): Cursor {
    val rootPageNum = table.rootPageNum
    val rootNode = getPage(table.pager, rootPageNum)

    if (getNodeType(rootNode) == TreeNodeType.NODE_LEAF) {
        return leafNodeFind(table, rootPageNum, key)
    } else {
        return internalNodeFind(table, rootPageNum, key)
    }
}

