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

sealed interface PrepareResult {
    data class SUCCESS(val statementType: StatementType) : PrepareResult
    object Unrecognized : PrepareResult
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
    if (command == "insert") {
        return PrepareResult.SUCCESS(StatementType.INSERT)
    } else if (command == "select") {
        return PrepareResult.SUCCESS(StatementType.SELECT)
    } else {
        return PrepareResult.Unrecognized
    }
}

fun executeStatement(statementType: StatementType) {
    when (statementType) {
        StatementType.INSERT -> {
            println("This is where we would do an insert.\n"); }

        StatementType.SELECT -> {
            println("This is where we would do an select.\n");
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
            is PrepareResult.SUCCESS -> {
                executeStatement(res.statementType)
            }

            is PrepareResult.Unrecognized -> {
                println("Unrecognized command: $command")
            }
        }
    }
}