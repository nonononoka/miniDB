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

const val INVALID_PAGE_NUM = Int.MAX_VALUE

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
        val rightChild = internalNodeRightChild(byteBuffer)
        // 相対getInt()はpositionを4進めてしまい，呼び出し側が読む位置がズレるので
        // 絶対indexで覗き見るだけにする
        if (rightChild.getInt(rightChild.position()) == INVALID_PAGE_NUM) {
            println("Tried to access right child, but was invalid page")
            exitProcess(1)
        }
        return rightChild
    } else {
        val child = internalNodeCell(byteBuffer, childNum)
        if (child.getInt(child.position()) == INVALID_PAGE_NUM) {
            println("Tried to access child $childNum, but was invalid page")
            exitProcess(1)
        }
        return child
    }
}

fun internalNodeKey(byteBuffer: ByteBuffer, keyNum: Int): ByteBuffer {
    // keyはcellの中でchildの後ろに置かれている
    return byteBuffer.position(internalNodeCell(byteBuffer, keyNum).position() + INTERNAL_NODE_CHILD_SIZE)
}

// bplustreeにおいて，一番右が一番大きいkey
// internal nodeだったら，ひたすら葉ノードにいくまで降りる
fun getNodeMaxKey(pager: Pager, node: ByteBuffer): Int {
    if (getNodeType(node) == TreeNodeType.NODE_LEAF) {
        return leafNodeKey(node, leafNodeNumCells(node).getInt() - 1).getInt()
    }
    val rightChild = getPage(pager, internalNodeRightChild(node).getInt())
    return getNodeMaxKey(pager, rightChild)
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
    internalNodeRightChild(byteBuffer).putInt(INVALID_PAGE_NUM)
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
    val childMaxKey = getNodeMaxKey(table.pager, child)
    val index = internalNodeFindChild(parent, childMaxKey)

    val originalNumKeys = internalNodeNumKeys(parent).getInt()

    // leafをsplitした結果，親ノードのinternal nodeのsplitが発生したとき
    if (originalNumKeys >= INTERNAL_NODE_MAX_CELLS) {
        // parentPageNumを分割した上でchildPageNumを突っ込む必要がある
        internalNodeSplitAndInsert(table, parentPageNum, childPageNum)
        return
    }

    // 今のright Childとright Child Page Num
    val rightChildPageNum = internalNodeRightChild(parent).getInt()
    // right childがINVALID_PAGE_NUMのinternal nodeは空っぽということ
    if (rightChildPageNum == INVALID_PAGE_NUM) {
        internalNodeRightChild(parent).putInt(childPageNum)
        return
    }
    val rightChild = getPage(table.pager, rightChildPageNum)
    internalNodeNumKeys(parent).putInt(originalNumKeys + 1)
    // Replace right child
    if (childMaxKey > getNodeMaxKey(table.pager, rightChild)) {
        // 今のrightChild
        internalNodeChild(parent, originalNumKeys).putInt(rightChildPageNum)
        internalNodeKey(parent, originalNumKeys).putInt(getNodeMaxKey(table.pager, rightChild))
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

// parent nodeが容量オーバーになったときに，それをsplitするための関数
// childは，親ノードに挿入しようとした結果、親ノードを分割するキッカケとなった新しい子ノード
fun internalNodeSplitAndInsert(table: Table, parentPageNum: Int, childPageNum: Int) {
    var oldPageNum = parentPageNum
    var oldNode = getPage(table.pager, parentPageNum)
    val oldMax = getNodeMaxKey(table.pager, oldNode)
    val child = getPage(table.pager, childPageNum)
    val childMax = getNodeMaxKey(table.pager, child)
    val newPageNum = getUnusedPageNum(table.pager)

    val splittingRoot = isNodeRoot(oldNode)

    val parent: ByteBuffer
    val newNode: ByteBuffer

    // 分割の際に新しく親を作る必要がある場合
    if (splittingRoot) {
        // newPageNumは，新しいrootのright childとしてinternal nodeに初期化される
        createNewRoot(table, newPageNum)
        // 新しく作ったルートページ
        parent = getPage(table.pager, table.rootPageNum)
        // 元のルートのデータは新しい子ページに丸ごとコピーされて
        // 元のルートデータ（oldNode）は，新しいルートとして上書きされる
        // なので分割元のnodeは，新しく指し直さなきゃいけない
        oldPageNum = internalNodeChild(parent, 0).getInt()
        oldNode = getPage(table.pager, oldPageNum)
        newNode = getPage(table.pager, newPageNum)
    } else {
        parent = getPage(table.pager, nodeParent(oldNode).getInt())
        newNode = getPage(table.pager, newPageNum)
        initializeInternalNode(newNode)
    }

    // 古いノードの右半分を新しいノードに移していく
    // 注意: numKeysは「positionをセットしたbyteBuffer」でしかないので変数に持ち回せない．
    // 読むときも書くときも毎回internalNodeNumKeys()で取り直すこと
    var curPageNum = internalNodeRightChild(oldNode).getInt()
    var cur = getPage(table.pager, curPageNum)

    internalNodeInsert(table, newPageNum, curPageNum)
    nodeParent(cur).putInt(newPageNum)
    // 古いノードのright childは空にする
    internalNodeRightChild(oldNode).putInt(INVALID_PAGE_NUM)

    for (i in INTERNAL_NODE_MAX_CELLS - 1 downTo INTERNAL_NODE_MAX_CELLS / 2 + 1) {
        curPageNum = internalNodeChild(oldNode, i).getInt()
        cur = getPage(table.pager, curPageNum)
        internalNodeInsert(table, newPageNum, curPageNum)
        nodeParent(cur).putInt(newPageNum)

        // 読みと書きは必ず別の文に分ける（同じbyteBufferなのでpositionが動く）
        val numKeys = internalNodeNumKeys(oldNode).getInt()
        internalNodeNumKeys(oldNode).putInt(numKeys - 1)
    }

    // 真ん中のkeyの左のchildが，今や一番大きいchildなのでright childにする
    val numKeys = internalNodeNumKeys(oldNode).getInt()
    val newRightChildPageNum = internalNodeChild(oldNode, numKeys - 1).getInt()
    internalNodeRightChild(oldNode).putInt(newRightChildPageNum)
    internalNodeNumKeys(oldNode).putInt(numKeys - 1)

    /*
    Determine which of the two nodes after the split should contain the child to be inserted,
    and insert the child
    */
    val maxAfterSplit = getNodeMaxKey(table.pager, oldNode)
    val destinationPageNum = if (childMax < maxAfterSplit) oldPageNum else newPageNum

    internalNodeInsert(table, destinationPageNum, childPageNum)
    nodeParent(child).putInt(destinationPageNum)

    val newOldMax = getNodeMaxKey(table.pager, oldNode)
    updateInternalNodeKey(parent, oldMax, newOldMax)

    if (!splittingRoot) {
        val oldParentPageNum = nodeParent(oldNode).getInt()
        // internalNodeInsertの中でさらにparentが分割されることがあり，
        // そのときはsplit側がnewNodeの親を正しく貼り直してくれるので，先に入れておく
        nodeParent(newNode).putInt(oldParentPageNum)
        internalNodeInsert(table, oldParentPageNum, newPageNum)
    }
}