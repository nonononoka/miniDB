package org.example

import java.nio.ByteBuffer
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*

class Pager(val fileChannel: FileChannel, var fileLength: Int) {
    val pages = arrayOfNulls<ByteBuffer>(TABLE_MAX_PAGES)
}

fun dbOpen(filename: String): Table {
    val file = File(filename)
    // 最初にchannelを開いてあとはそれを使い回す
    val channel = FileChannel.open(file.toPath(), CREATE, READ, WRITE)
    val pager = Pager(channel, file.length().toInt())
    // 今，ファイルにあるrowの数
    val numRows = pager.fileLength / ROW_SIZE
    val table = Table(numRows, pager)
    return table
}

fun dbClose(table: Table) {
    val pager = table.pager
    // 全部で何ページあるか
    val numFullPages = table.numRows / ROWS_PER_PAGE

    for (i in 0 until numFullPages) {
        if (pager.pages[i] == null) {
            continue
        }
        // pageをファイルに書き戻す
        pagerFlush(table.pager, i, PAGE_SIZE)
        pager.pages[i] = null
    }

    // 最後の，1ページ分に満たない中途半端なページも書き込む
    val numAdditionalRow = table.numRows % ROWS_PER_PAGE
    if(numAdditionalRow != 0){
        val pageNum = numFullPages
        if (pager.pages[pageNum] != null){
            pagerFlush(pager, numFullPages, numAdditionalRow * ROW_SIZE)
            pager.pages[numFullPages] = null
        }
    }

    pager.fileChannel.close()
}

fun pagerFlush(pager: Pager, pageNum: Int, size: Int) {
    if (pager.pages[pageNum] == null) {
        println("Tried to flush null page")
    }

    // pager.pages[pageNum]をpager.fileに書き込む
    val page = pager.pages[pageNum]!!
    page.position(0)
    page.limit(size)
    val offset = (pageNum * PAGE_SIZE).toLong()
    pager.fileChannel.write(page, offset)
}

// 最初に全部loadしてくるわけではなくて必要になったときにメモリにloadしてくる
// 指定された番号のページを返す，もしメモリに無ければファイルから探し出してメモリに載せる
fun getPage(pager: Pager, pageNum: Int): ByteBuffer {
    if (pageNum > TABLE_MAX_PAGES) {
        println("Tried to fetch page number out of bounds. $pageNum > $TABLE_MAX_PAGES")
    }

    // cache miss. Allocate memory and load from file
    if (pager.pages[pageNum] == null) {
        // そのページ用の空きメモリを作る
        val page = ByteBuffer.allocate(PAGE_SIZE)
        // 今，ファイル内に何ページあるか
        var numPages = pager.fileLength / PAGE_SIZE
        if (pager.fileLength % PAGE_SIZE != 0) {
            numPages += 1
        }

        // pageNum < numPagesなら，ファイルにデータがあるってことだから，読み込む
        if (pageNum < numPages) {
            // PAGE_SIZE分だけ，読み込む
            val offset = (pageNum * PAGE_SIZE).toLong()
            pager.fileChannel.read(page, offset)
        }

        pager.pages[pageNum] = page
    }
    return pager.pages[pageNum]!!
}
