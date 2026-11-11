# Changelog — RadarLite

Todas as alterações notáveis deste projeto serão documentadas neste arquivo.

## [1.2.8] - 2026-11-11 (Build 10)

### Adicionado
- **Aviso Visual Transitório de Perda de GPS:** O card "📡 Sinal GPS Perdido" agora é transitório, ocultando-se suavemente após 3,5 segundos sem poluir a visão do motorista nem bloquear a tela da multimídia.
- **Aviso de Restauração de Sinal GPS:** Novo card com destaque verde "🛰️ Sinal GPS Recuperado - Conexão restabelecida", também exibido por 3,5 segundos ao recuperar o sinal de satélite.
- **Cooldown de 3 Minutos para GPS:** Evita que a mensagem de perda de sinal reapareça repetidamente durante oscilações em túneis ou cânions urbanos próximos.
- **Sistema de 3 Strikes por Passagem:** O botão "X" do card agora oculta o alerta imediatamente para a passagem atual e acumula strikes persistentes em `SharedPreferences`. O banimento definitivo no sentido da via é efetivado apenas no 3º descarte em viagens distintas.

### Corrigido
- **Autostart por Bluetooth (Samsung Galaxy S25 / Centrais Multimídia):**
  - Corrigido o bloqueio onde paradas anteriores por desconexão salvavam `isServiceIntentionallyStopped = true`, impedindo autostart futuro ao religar o Bluetooth.
  - O receiver agora limpa a flag de parada intencional quando o smartphone pareado conecta.
  - `startServiceSilently` agora utiliza a `InvisibleStartActivity` (Trampoline Activity com `FLAG_ACTIVITY_NEW_TASK`), contornando restrições de Background Foreground Service do Android 8 a 15 sem exceções.
  - Validação inteligente e flexível do dispositivo alvo por endereço MAC ou Nome (com suporte a variações de modelo como "Galaxy S25" e fallback para `bondedDevices`).
- **Erro de compilação em `PreferencesManager.kt`:** Corrigido espaçamento acidental no setter de `isNightModeEnabled`.

---

## [1.2.7] - 2026-11-06 (Build 9)

### Adicionado
- **Priorização Heurística de Radares:** Radares com limite de velocidade real (> 0 km/h) têm prioridade sobre radares puramente informativos (0 km/h). Radares na trajetória frontal ("Na sua via") têm prioridade sobre transversais.
- **Filtro de Sobrevelocidade para 0 km/h:** Pontos com 0 km/h nunca ativam o estado de sobrevelocidade (`isOverSpeed`).
- **Modo Noturno Unificado:** Opção de tema global (Auto, Dia, Noite) integrado a `AppCompatDelegate.setDefaultNightMode()`.
- **Barra de Progresso de Distância:** Indicador visual de proximidade no card de alerta em Jetpack Compose.
- **Sinalização para Revisão (Flagging):** Botão discreto (🚩) para marcar radares suspeitos no SharedPreferences (`flagged_for_review`).

---

## [1.2.6] - 2026-11-06 (Build 8)

### Adicionado
- **Rota Expressa de Devolução (Foreground Broadcast):** Emissão de `ACTION_STATE_CHANGED` com `FLAG_RECEIVER_FOREGROUND` e `FLAG_INCLUDE_STOPPED_PACKAGES`.
- **Sincronização em Tempo Real com Launcher:** Resposta imediata a pings remotos `ACTION_REQUEST_STATE`.
- **Compatibilidade Dupla de Namespaces:** Transmissão em `com.jozito412` e `com.jozito`.

---

## [1.2.5] - 2026-11-06 (Build 7)

### Corrigido
- **Trava Síncrona do Cão de Guarda:** Gravação síncrona de `is_service_intentionally_stopped = true` ao receber `ACTION_STOP`.
- **Contrato do OS Imediato:** Chamada de `startForeground()` na primeira linha do `onCreate()`.
- **Encerramento Seguro:** Chamada direta a `stopForeground(STOP_FOREGROUND_REMOVE)` seguida de `stopSelf()`.
