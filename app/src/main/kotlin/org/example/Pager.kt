package org.example

import java.nio.ByteBuffer
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import kotlin.system.exitProcess

class Pager(val fileChannel: FileChannel, var fileLength: Int, var numPages: Int) {
    // page1つ1つが，btreeのnodeに相当する
    val pages = arrayOfNulls<ByteBuffer>(TABLE_MAX_PAGES)
}

fun dbOpen(filename: String): Table {
    val file = File(filename)
    // 最初にchannelを開いてあとはそれを使い回す
    val channel = FileChannel.open(file.toPath(), CREATE, READ, WRITE)
    val pager = Pager(channel, file.length().toInt(), file.length().toInt() / PAGE_SIZE)

    if (file.length().toInt() % PAGE_SIZE != 0) {
        println("Db file is not a whole number of pages. Corrupt file.")
        exitProcess(1)
    }

    val table = Table(pager, 0)
    if (pager.numPages == 0) {
        // New database file．Initialize page 0 as leaf node.
        val rootNode = getPage(pager, 0)
        initializeLeafNode(rootNode)
    }
    return table
}

fun dbClose(table: Table) {
    val pager = table.pager
    // 全部で何ページあるか
    for (i in 0 until pager.numPages) {
        if (pager.pages[i] == null) {
            continue
        }
        // pageをファイルに書き戻す
        pagerFlush(table.pager, i)
        pager.pages[i] = null
    }

    pager.fileChannel.close()
}

fun pagerFlush(pager: Pager, pageNum: Int) {
    if (pager.pages[pageNum] == null) {
        println("Tried to flush null page")
    }

    // pager.pages[pageNum]をpager.fileに書き込む
    val page = pager.pages[pageNum]!!
    page.position(0)
    val offset = (pageNum * PAGE_SIZE).toLong()
    // page（ByteBuffer）の中身を、ファイルの先頭から offset バイト目の位置に書き込む
    pager.fileChannel.write(page, offset)
}

// 最初に全部loadしてくるわけではなくて必要になったときにメモリにloadしてくる
// 指定された番号のページを返す，もしメモリに無ければファイルから探し出してメモリに載せる
// ここはpageNum番目のpageを特定のByteBufferに読み込んでいるだけなので，
// treeにしようが関係ない
// pageNum番目のtreenodeを返しているみたいなこと
fun getPage(pager: Pager, pageNum: Int): ByteBuffer {
    // pageNumは，0-index始まりのpage番号
    // pager.numPagesは，
    if (pageNum > TABLE_MAX_PAGES) {
        println("Tried to fetch page number out of bounds. $pageNum > $TABLE_MAX_PAGES")
        exitProcess(1)
    }

    // cache miss. Allocate memory and load from file
    if (pager.pages[pageNum] == null) {
        // そのページ用の空きメモリを作る
        val page = ByteBuffer.allocate(PAGE_SIZE)

        // pageNum < numPagesなら，まだファイルに読み込んでいないデータが
        // あって，その分のpageを要求しているってことだからそれを読み込めばいい
        if (pageNum < pager.numPages) {
            // PAGE_SIZE分だけ，読み込む
            val offset = (pageNum * PAGE_SIZE).toLong()
            pager.fileChannel.read(page, offset)
        } else {
            // そうじゃない場合は，pageを増やすことになるから，numPagesを増やす
            pager.numPages = pageNum + 1
        }

        pager.pages[pageNum] = page
    }
    return pager.pages[pageNum]!!
}
