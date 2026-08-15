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
const val LEAF_NODE_NEXT_LEAF_SIZE = 4
const val LEAF_NODE_NEXT_LEAF_OFFSET = LEAF_NODE_NUM_CELLS_OFFSET + LEAF_NODE_NUM_CELLS_SIZE
const val LEAF_NODE_HEADER_SIZE = COMMON_NODE_HEADER_SIZE + LEAF_NODE_NUM_CELLS_SIZE + LEAF_NODE_NEXT_LEAF_SIZE

// Leaf Node Body Layout
const val LEAF_NODE_KEY_SIZE = 4
const val LEAF_NODE_KEY_OFFSET = 0
const val LEAF_NODE_VALUE_SIZE = ROW_SIZE
const val LEAF_NODE_VALUE_OFFSET = LEAF_NODE_KEY_OFFSET + LEAF_NODE_KEY_SIZE
const val LEAF_NODE_CELL_SIZE = LEAF_NODE_KEY_SIZE + LEAF_NODE_VALUE_SIZE
const val LEAF_NODE_SPACE_FOR_CELLS = PAGE_SIZE - LEAF_NODE_HEADER_SIZE
const val LEAF_NODE_MAX_CELLS = LEAF_NODE_SPACE_FOR_CELLS / LEAF_NODE_CELL_SIZE
const val LEAF_NODE_RIGHT_SPLIT_COUNT = (LEAF_NODE_MAX_CELLS + 1) / 2;
const val LEAF_NODE_LEFT_SPLIT_COUNT = (LEAF_NODE_MAX_CELLS + 1) - LEAF_NODE_RIGHT_SPLIT_COUNT;

// utility関数（leaf node version）
fun leafNodeNumCells(byteBuffer: ByteBuffer): ByteBuffer {
    return byteBuffer.position(LEAF_NODE_NUM_CELLS_OFFSET)
}

fun leafNodeNextLeaf(byteBuffer: ByteBuffer): ByteBuffer {
    return byteBuffer.position(LEAF_NODE_NEXT_LEAF_OFFSET)
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
    setNodeRoot(byteBuffer, false)
    leafNodeNumCells(byteBuffer).putInt(0)
    leafNodeNextLeaf(byteBuffer).putInt(0)
}

fun nodeParent(byteBuffer: ByteBuffer): ByteBuffer {
    return byteBuffer.position(PARENT_POINTER_OFFSET)
}

// 実際にinsertする関数
fun leafNodeInsert(cursor: Cursor, key: Int, value: Row) {
    val node = getPage(cursor.table.pager, cursor.pageNum)
    val numCells = leafNodeNumCells(node).getInt()
    if (numCells >= LEAF_NODE_MAX_CELLS) {
        leafNodeSplitAndInsert(cursor, key, value)
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

fun leafNodeSplitAndInsert(cursor: Cursor, key: Int, value: Row) {
    // oldNodeは今から分割しようとしているnode
    val oldNode = getPage(cursor.table.pager, cursor.pageNum)
    val oldMax = getNodeMaxKey(cursor.table.pager, oldNode)
    val newPageNum = getUnusedPageNum(cursor.table.pager)
    // 新しいpageを作る（=新しいnodeを作る）．これがright child nodeになる
    val newNode = getPage(cursor.table.pager, newPageNum)
    initializeLeafNode(newNode)
    nodeParent(newNode).putInt(nodeParent(oldNode).getInt())
    // oldNode→newNode→oldNodeが元々指していたやつ，っていう順番にする
    leafNodeNextLeaf(newNode).putInt(leafNodeNextLeaf(oldNode).getInt())
    leafNodeNextLeaf(oldNode).putInt(newPageNum)
    var destinationNode: ByteBuffer;

    // oldNode内のそれぞれの(key, value)をoldNodeとnewNodeの二つに分割する
    for (i in LEAF_NODE_MAX_CELLS downTo 0) {
        if (i >= LEAF_NODE_LEFT_SPLIT_COUNT) {
            destinationNode = newNode
        } else {
            destinationNode = oldNode
        }
        val indexWithinNode = i % LEAF_NODE_LEFT_SPLIT_COUNT

        // まさに今挿入しようとしているものだったら
        if (i == cursor.cellNum) {
            serializeRow(value, leafNodeValue(destinationNode, indexWithinNode))
            leafNodeKey(destinationNode, indexWithinNode).putInt(key)
        } else {
            // 元々i-1の位置にあったものをdestinationにずらす
            // 0,1,2,4,5で，3を挿入しようとしたとき
            // 4,5は元々cellNumで3,4の位置にあったもの．
            val sourceIndex = if (i > cursor.cellNum) i - 1 else i
            // destinationNodeとoldNodeが同一インスタンスのことがあるので，
            // 読み終わってから書き込み先のpositionを取り直す
            val tmp = ByteArray(LEAF_NODE_CELL_SIZE)
            leafNodeCell(oldNode, sourceIndex).get(tmp)
            leafNodeCell(destinationNode, indexWithinNode).put(tmp)
        }
    }

    leafNodeNumCells(oldNode).putInt(LEAF_NODE_LEFT_SPLIT_COUNT)
    leafNodeNumCells(newNode).putInt(LEAF_NODE_RIGHT_SPLIT_COUNT)

    // 分割した元のnodeがrootだったら，新しくrootNodeを作って，それのchildを
    // oldNodeと上で新しく作ったnewNodeにする
    if (isNodeRoot(oldNode)) {
        return createNewRoot(cursor.table, newPageNum)
    } else {
        // parentが元々のsplitされたノードの親
        val parentPageNum = nodeParent(oldNode).getInt()
        val newMax = getNodeMaxKey(cursor.table.pager, oldNode)
        val parentNode = getPage(cursor.table.pager, parentPageNum)
        updateInternalNodeKey(parentNode, oldMax, newMax)
        internalNodeInsert(cursor.table, parentPageNum, newPageNum)
    }
}

fun createNewRoot(table: Table, rightChildPageNum: Int) {
    // すでに右半分は移動済み
    val root = getPage(table.pager, table.rootPageNum) // これは今のroot node
    val rightChild = getPage(table.pager, rightChildPageNum) // うつした先のnode
    // 左半分を退避させる
    // 元のrootページには，まだ下半分のデータが残っている
    // left childを作って，そこにそっくりそのままコピーして退避する
    val leftChildPageNum = getUnusedPageNum(table.pager)
    val leftChild = getPage(table.pager, leftChildPageNum)

    // 元のrootがinternal nodeだった場合（internal nodeのsplit）は，
    // これから使う2つの子ページをinternal nodeとして初期化しておく
    if (getNodeType(root) == TreeNodeType.NODE_INTERNAL) {
        initializeInternalNode(rightChild)
        initializeInternalNode(leftChild)
    }

    val tmp = ByteArray(PAGE_SIZE)
    root.position(0)
    root.get(tmp)
    leftChild.position(0)
    leftChild.put(tmp)
    setNodeRoot(leftChild, false)

    // leftChildは別のページに引っ越したので，その子供たちの親ポインタを貼り直す
    if (getNodeType(leftChild) == TreeNodeType.NODE_INTERNAL) {
        val numKeys = internalNodeNumKeys(leftChild).getInt()
        for (i in 0 until numKeys) {
            val grandChild = getPage(table.pager, internalNodeChild(leftChild, i).getInt())
            nodeParent(grandChild).putInt(leftChildPageNum)
        }
        val rightGrandChild = getPage(table.pager, internalNodeRightChild(leftChild).getInt())
        nodeParent(rightGrandChild).putInt(leftChildPageNum)
    }

    // 中身を退避させて空き部屋になった元のrootページを
    // 初期化して，新しいノードとして再利用する
    initializeInternalNode(root)
    setNodeRoot(root, true)
    internalNodeNumKeys(root).putInt(1)
    // キーの押し上げをして，新しいrootページにセットして，二つの子供をぶら下げる
    val leftChildMaxKey = getNodeMaxKey(table.pager, leftChild) // 新しいkey
    internalNodeKey(root, 0).putInt(leftChildMaxKey)
    internalNodeChild(root, 0).putInt(leftChildPageNum)
    internalNodeRightChild(root).putInt(rightChildPageNum)
    nodeParent(leftChild).putInt(table.rootPageNum)
    nodeParent(rightChild).putInt(table.rootPageNum)
}

fun getUnusedPageNum(pager: Pager): Int {
    return pager.numPages
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