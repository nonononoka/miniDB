package org.example

import kotlin.system.exitProcess

enum class MetaCommandResult {
    META_COMMAND_SUCCESS,
    META_COMMAND_UNRECOGNIZED,
}

enum class StatementType {
    INSERT,
    SELECT
}

val Pages = mutableListOf<Row>()

data class Row(val id: Int, val name: String, val email: String)

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

fun executeInsert(row: Row): ExecuteResult {
    Pages.add(row)
    return ExecuteResult.EXECUTE_SUCCESS
}

fun executeSelect(): ExecuteResult {
    for ((id, name, email) in Pages) {
        println("id: ${id}, name: ${name}, email: ${email}")
    }
    return ExecuteResult.EXECUTE_SUCCESS
}

fun executeStatement(statement: Statement): ExecuteResult {
    when (statement) {
        is Statement.InsertStatement -> {
            return executeInsert(statement.rowToInsert)
        }

        is Statement.SelectStatement -> {
            return executeSelect()
        }
    }
}


fun main() {
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
                when (executeStatement(res.statement)) {
                    ExecuteResult.EXECUTE_SUCCESS -> {
                        println("Executed successfully")
                    }

                    ExecuteResult.EXECUTE_TABLE_FAILURE -> {
                        println("Table failure")
                    }
                }
            }

            is PrepareResult.SELECT_SUCCESS -> {
                when (executeStatement(res.statement)) {
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