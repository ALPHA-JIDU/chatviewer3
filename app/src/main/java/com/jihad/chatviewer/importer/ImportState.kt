package com.jihad.chatviewer.importer

data class ImportUiState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val text: String = "",
    val done: Long = 0L,
    val total: Long = 0L,
    val failed: String = "",
    val resultText: String = "",
    val unreadable: List<String> = emptyList()
)
