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

class Table(var numRows: Int) {
    val pages = arrayOfNulls<ByteBuffer>(TABLE_MAX_PAGES).toMutableList()
}

enum class StatementType {
    INSERT,
    SELECT
}

data class Row(val id: Int, val name: String, val email: String)

fun serializeRow(row: Row, pageNum: Int, byteOffset: Int, table: Table) {
    // positionで位置を指定したあとは，相対putで埋めていく
    val page = table.pages[pageNum]!!
    page.position(byteOffset)
    page.putInt(row.id)
    // 文字コード書いていない場合は，Charsets.UTF_8が指定される
    val userNameBytes = row.name.toByteArray().copyOf(COLUMN_USERNAME_SIZE)
    page.put(userNameBytes)
    val emailBytes = row.email.toByteArray().copyOf(COLUMN_EMAIL_SIZE)
    page.put(emailBytes)
}

fun deserializeRow(pageNum: Int, byteOffset: Int, table: Table): Row {
    val page = table.pages[pageNum]!!
    page.position(byteOffset)
    val id = page.getInt()
    val userNameBytes = ByteArray(COLUMN_USERNAME_SIZE)
    page.get(userNameBytes)
    val name = String(userNameBytes).trimEnd('\u0000')

    val emailBytes = ByteArray(COLUMN_EMAIL_SIZE)
    page.get(emailBytes)
    val email = String(emailBytes).trimEnd('\u0000')
    return Row(id, name, email)
}

fun rowSlot(table: Table, rowNum: Int): Pair<Int, Int> {
    val pageNum = rowNum / ROWS_PER_PAGE
    val page = table.pages[pageNum]
    // まだ確保できていなかったら新しいpageを確保
    if (page == null) {
        table.pages[pageNum] = ByteBuffer.allocate(PAGE_SIZE)
    }
    val rowOffset = rowNum % ROWS_PER_PAGE
    val byteOffset = rowOffset * ROW_SIZE
    return Pair(pageNum, byteOffset)
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

fun doMetaCommand(command: String): MetaCommandResult {
    if (command == ".exit") {
        exitProcess(0)
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
    if (table.numRows >= TABLE_MAX_ROWS) {
        return ExecuteResult.EXECUTE_TABLE_FAILURE
    }
    val (pageNum, byteOffset) = rowSlot(table, table.numRows)
    serializeRow(row, pageNum, byteOffset, table)
    table.numRows += 1
    return ExecuteResult.EXECUTE_SUCCESS
}

fun executeSelect(table: Table): ExecuteResult {
    for (i in 0..<table.numRows) {
        val (pageNum, byteOffset) = rowSlot(table, i)
        val row = deserializeRow(pageNum, byteOffset, table)
        println("id: ${row.id}, name: ${row.name}, email: ${row.email}")
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


fun main() {
    val table = Table(0)

    while (true) {
        print("db > ")
        val command = readLine()!!

        // meta command
        if (command[0] == '.') {
            when (doMetaCommand(command)) {
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