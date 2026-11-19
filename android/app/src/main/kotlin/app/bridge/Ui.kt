package app.bridge

object Ui {
    fun statusLine(m: ConnectionManager): String = when (m.state) {
        ConnectionManager.State.UNPAIRED -> "Не сопряжено"
        ConnectionManager.State.PAIRING -> "Сопряжение…"
        ConnectionManager.State.SEARCHING -> "Поиск Mac в сети…"
        ConnectionManager.State.CONNECTING -> "Подключение…"
        ConnectionManager.State.CONNECTED -> "Подключено: " + (m.peerName ?: "Mac")
        ConnectionManager.State.ASLEEP -> if (m.macAsleep) "Mac спит" else "Mac недоступен"
    }
}
