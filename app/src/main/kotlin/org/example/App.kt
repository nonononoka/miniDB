package org.example

import kotlin.system.exitProcess
import java.nio.ByteBuffer

const val COLUMN_USERNAME_SIZE = 32
const val COLUMN_EMAIL_SIZE = 255
const val ID_SIZE = 4
const val USER_NAME_SIZE = COLUMN_USERNAME_SIZE
const val EMAIL_SIZE = COLUMN_EMAIL_SIZE

const val ID_OFFSET = 0
const val USER_NAME_OFFSET = ID_OFFSET + ID_SIZE
const val EMAIL_OFFSET = USER_NAME_OFFSET + USER_NAME_SIZE
const val ROW_SIZE = ID_SIZE + USER_NAME_SIZE + EMAIL_SIZE

const val PAGE_SIZE = 4096
const val TABLE_MAX_PAGES = 100
const val ROWS_PER_PAGE = PAGE_SIZE / ROW_SIZE
const val TABLE_MAX_ROWS = ROWS_PER_PAGE * TABLE_MAX_PAGES

enum class MetaCommandResult {
    META_COMMAND_SUCCESS,
    META_COMMAND_UNRECOGNIZED,
}

class Table(val pager: Pager, var rootPageNum: Int)

enum class StatementType {
    INSERT,
    SELECT
}

data class Row(val id: Int, val name: String, val email: String)

fun serializeRow(row: Row, page: ByteBuffer) {
    // positionで位置を指定したあとは，相対putで埋めていく
    page.putInt(row.id)
    // 文字コード書いていない場合は，Charsets.UTF_8が指定される
    val userNameBytes = row.name.toByteArray().copyOf(COLUMN_USERNAME_SIZE)
    page.put(userNameBytes)
    val emailBytes = row.email.toByteArray().copyOf(COLUMN_EMAIL_SIZE)
    page.put(emailBytes)
}

fun deserializeRow(page: ByteBuffer): Row {
    val id = page.getInt()
    val userNameBytes = ByteArray(COLUMN_USERNAME_SIZE)
    page.get(userNameBytes)
    val name = String(userNameBytes).trimEnd('\u0000')

    val emailBytes = ByteArray(COLUMN_EMAIL_SIZE)
    page.get(emailBytes)
    val email = String(emailBytes).trimEnd('\u0000')
    return Row(id, name, email)
}

fun cursorValue(cursor: Cursor): ByteBuffer {
    val pageNum = cursor.pageNum
    val page = getPage(cursor.table.pager, pageNum)
    return leafNodeValue(page, cursor.cellNum)
}

sealed interface Statement {
    data class InsertStatement(override val statementType: StatementType, val rowToInsert: Row) : Statement
    data class SelectStatement(override val statementType: StatementType) : Statement

    val statementType: StatementType
}

sealed interface PrepareResult {
    data class INSERT_SUCCESS(val statement: Statement.InsertStatement) : PrepareResult
    data class SELECT_SUCCESS(val statement: Statement.SelectStatement) : PrepareResult
    object Unrecognized : PrepareResult
    object SYNTAX_ERROR : PrepareResult
}

enum class ExecuteResult {
    EXECUTE_SUCCESS,
    EXECUTE_TABLE_FAILURE,
}

fun doMetaCommand(command: String, table: Table): MetaCommandResult {
    if (command == ".exit") {
        dbClose(table)
        exitProcess(0)
    } else if (command == ".constants") {
        println("Constants:")
        printConstants()
        return MetaCommandResult.META_COMMAND_SUCCESS
    } else {
        return MetaCommandResult.META_COMMAND_UNRECOGNIZED
    }
}

// どういうcommandかを解釈するだけ．実行はまだ
fun prepareStatement(command: String): PrepareResult {
    val argList = command.split(" ")
    if (argList[0] == "insert") {
        if (argList.size != 4) {
            return PrepareResult.SYNTAX_ERROR
        }
        val argList = command.split(" ")
        return PrepareResult.INSERT_SUCCESS(
            Statement.InsertStatement(
                StatementType.INSERT,
                Row(argList[1].toInt(), argList[2], argList[3])
            )
        )
    } else if (argList[0] == "select") {
        return PrepareResult.SELECT_SUCCESS(Statement.SelectStatement(StatementType.SELECT))
    } else {
        return PrepareResult.Unrecognized
    }
}

fun executeInsert(row: Row, table: Table): ExecuteResult {
    val node = getPage(table.pager, table.rootPageNum)
    if (leafNodeNumCells(node).getInt() >= TABLE_MAX_ROWS) {
        return ExecuteResult.EXECUTE_TABLE_FAILURE
    }
    val cursor = tableEnd(table)
    leafNodeInsert(cursor, row.id, row)
    return ExecuteResult.EXECUTE_SUCCESS
}

fun executeSelect(table: Table): ExecuteResult {
    val cursor = tableStart(table)
    while (!cursor.endOfTable) {
        val row = deserializeRow(cursorValue(cursor))
        println("id: ${row.id}, name: ${row.name}, email: ${row.email}")
        cursor.advance()
    }

    return ExecuteResult.EXECUTE_SUCCESS
}

fun executeStatement(statement: Statement, table: Table): ExecuteResult {
    when (statement) {
        is Statement.InsertStatement -> {
            return executeInsert(statement.rowToInsert, table)
        }

        is Statement.SelectStatement -> {
            return executeSelect(table)
        }
    }
}

fun printConstants() {
    println("ROW_SIZE: $ROW_SIZE")
    println("COMMON_NODE_HEADER_SIZE: $COMMON_NODE_HEADER_SIZE")
    println("LEAF_NODE_HEADER_SIZE: $LEAF_NODE_HEADER_SIZE")
    println("LEAF_NODE_CELL_SIZE: $LEAF_NODE_CELL_SIZE")
    println("LEAF_NODE_SPACE_FOR_CELLS: $LEAF_NODE_SPACE_FOR_CELLS")
    println("LEAF_NODE_MAX_CELLS: $LEAF_NODE_MAX_CELLS")
}

fun main() {
    val table = dbOpen("test.db")

    while (true) {
        print("db > ")
        val command = readLine()!!

        // meta command
        if (command[0] == '.') {
            when (doMetaCommand(command, table)) {
                MetaCommandResult.META_COMMAND_SUCCESS -> {
                    continue;
                }

                MetaCommandResult.META_COMMAND_UNRECOGNIZED -> {
                    println("Unrecognized command: $command")
                    continue;
                }
            }
        }

        // normal command
        when (val res = prepareStatement(command)) {
            is PrepareResult.Unrecognized -> {
                println("Unrecognized command: $command")
                continue;
            }

            is PrepareResult.SYNTAX_ERROR -> {
                println("Syntax.error: $command")
                continue;
            }

            is PrepareResult.INSERT_SUCCESS -> {
                when (executeStatement(res.statement, table)) {
                    ExecuteResult.EXECUTE_SUCCESS -> {
                        println("Executed successfully")
                    }

                    ExecuteResult.EXECUTE_TABLE_FAILURE -> {
                        println("Table failure")
                    }
                }
            }

            is PrepareResult.SELECT_SUCCESS -> {
                when (executeStatement(res.statement, table)) {
                    ExecuteResult.EXECUTE_SUCCESS -> {
                        println("Executed successfully")
                    }

                    ExecuteResult.EXECUTE_TABLE_FAILURE -> {
                        println("Table failure")
                    }
                }
            }
        }
    }
}