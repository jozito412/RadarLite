<p align="center">
  <img src="./RadarLite_icon.png" width="128" height="128" alt="RadarLite Icon" />
</p>

# 🚗 RadarLite — Monitoramento Automotivo Inteligente de Radares (v1.0)

[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![Room](https://img.shields.io/badge/Room-SQLite%20Offline-FF6F00)](#)
[![Versão](https://img.shields.io/badge/Versão-1.0-brightgreen)](#)

**RadarLite** é um aplicativo Android veicular de alta performance, leve, 100% offline e com interface moderna em **Jetpack Compose**. Projetado especificamente para motoristas e centrais multimídia automotivas, ele funciona como uma camada flutuante inteligente (*overlay*) sobre o Waze, Google Maps ou qualquer outro app de navegação.

---

## 📦 Download do Instalador

Você pode baixar diretamente o instalador compilado da versão 1.0:
- 📲 **[RadarLite.apk](./RadarLite.apk)** (Instalador oficial pronto para Android)

---

## 🌟 Principais Recursos

### 1. 🪟 Janela Flutuante Inteligente (Overlay HUD)
- **Card Dinâmico em 3 Estágios:**
  - 🟢 **Modo Normal:** Alerta discreto quando a velocidade do veículo está dentro do limite da via, exibindo placa oficial de trânsito, distância em tempo real e tipo do radar.
  - 🟡 **Alerta Estático (Atenção Antecipada):** Fundo vermelho sólido e claro que avisa com suavidade que o veículo ultrapassou o limite enquanto ainda há tempo hábil para desaceleração suave.
  - 🔴 **Alerta Agressivo (Redução Urgente):** Pulso visual animado com escala periférica para situações de proximidade crítica com excesso de velocidade.
- **Modo Noturno & Modo HUD:**
  - Card escurecido de alto contraste para não ofuscar o condutor à noite.
  - Modo HUD espelhado horizontalmente para projeção nítida das informações no para-brisa do carro.

### 2. ⚡ Desempenho e Blindagem Anti-Superaquecimento
- **Cache Espacial em RAM (Anti-Throttling):**
  - O aplicativo realiza consultas ao banco Room em raios amplos de 15 km e mantém os radares em memória RAM. O banco só é reconsultado ao percorrer mais de 10 km, economizando 99% de I/O de disco e evitando o superaquecimento do smartphone ou da multimídia.
- **Watchdog de Perda de GPS:**
  - Em túneis, garagens subterrâneas ou áreas de sombra, detecta ausência de sinal GPS por mais de 5 segundos, ocultando imediatamente alertas falsos e exibindo sinalizador discreto de busca por satélite.
- **Serviço em Primeiro Plano Blindado:**
  - Foreground Service prioritário com notificação permanente, protegido contra fechamento indevido pelo sistema operacional.

### 3. 🚗 Automação Veicular (Bluetooth Auto-Start / Auto-Stop)
- **Hands-Free Total:**
  - Selecione o dispositivo Bluetooth do som ou multimídia do seu veículo nas configurações.
  - Ao ligar o carro e conectar no Bluetooth, o RadarLite inicia o serviço flutuante automaticamente.
  - Ao desligar o veículo e desconectar, o serviço é pausado e liberado para economizar bateria.
  - Mecanismo de verificação ativa com *heartbeat* para impedir interrupções indesejadas em saídas rápidas do carro.

### 4. 🛡️ Curadoria Manual & UX da Blacklist (Fase 9 & 9.1)
- **Botão Gigante de 72dp:**
  - Botão com área de toque ampla de `72.dp` e ícone bem visível para ignorar falsos positivos com total segurança ao volante.
  - Confirmação visual instantânea via `Toast` do sistema.
- **Transparência de Fonte:**
  - Exibe a origem do radar diretamente no card (ex: `OSM`, `DNIT`, `ANTT`).
- **Gerenciador de Radares Ignorados:**
  - Painel na tela principal para consultar todos os radares banidos, restaurar radares individualmente ou limpar toda a blacklist com um único toque.
- **Filtro Global de Falsos Positivos:**
  - Opção para ignorar em massa radares cadastrados pelo OpenStreetMap caso o usuário prefira apenas bases homologadas.

### 5. 📏 Distâncias Customizáveis por Faixa de Velocidade
Ajuste fino individualizado para disparar o aviso na distância ideal para a sua velocidade de condução:
- 🔵 **Vias Lentas (até 40 km/h):** Padrão 180m
- 🟢 **Vias Urbanas (41 a 60 km/h):** Padrão 220m
- 🟡 **Vias Expressas (61 a 90 km/h):** Padrão 350m
- 🔴 **Rodovias (acima de 90 km/h):** Padrão 500m

### 6. 🔄 Atualização Segura (Safe Swap)
- Sincronização direta com a base consolidada dos estados **PR, SC, SP e MS**.
- **Safe Swap:** O download e a conversão dos dados são validados em banco temporário. Se houver falha de rede ou dados corrompidos, a base anterior continua 100% funcional sem interrupções.

---

## 🛠️ Permissões Necessárias

Para o correto funcionamento do aplicativo, conceda as seguintes permissões:
1. **Localização em Segundo Plano:** Escolha a opção *"Permitir o tempo todo"* para que o GPS possa monitorar os radares enquanto outros aplicativos (Waze/Maps) estão em foco.
2. **Sobreposição sobre outros aplicativos:** Permite que a janela flutuante com a placa de velocidade apareça sobre a navegação.
3. **Dispositivos Próximos (Bluetooth Connect):** Necessário a partir do Android 12 para ativar o auto-start ao parear com o rádio do carro.
4. **Notificações:** Mantém o serviço do Android ativo em primeiro plano com prioridade do sistema.

---

## 🏗️ Como Compilar o Projeto

### Pré-requisitos
- JDK 17 ou superior
- Android SDK com compileSdk 37
- Gradle 8.5+

### Linha de Comando
```bash
# Clonar o repositório
git clone https://github.com/jozito412/RadarLite.git
cd RadarLite

# Compilar e gerar o instalador RadarLite.apk
./gradlew assembleDebug

# O APK final é gerado na raiz do projeto:
# ./RadarLite.apk
```

---

## 📱 Tecnologias Utilizadas

- **Linguagem:** Kotlin 2.0+
- **UI Toolkit:** Jetpack Compose & Material 3
- **Banco de Dados:** Room Database (SQLite) com consultas geoespaciais e Safe Swap
- **Serviços:** Android Lifecycle Service & WindowManager Overlay
- **Geolocalização:** Google Play Services Location API
- **Concorrência:** Kotlin Coroutines & StateFlow

---

## 📄 Licença e Créditos

Desenvolvido por **Jozito**.
Dados de radares públicos e abertos consolidados das bases estaduais e comunitárias brasileiras.
