# e2player e TVH Player para Android TV

Players de TV ao estilo MAG (Infomir), com a mesma interface e duas fontes de canais:

- **e2player** – boxes Enigma2 (OpenWebif), com o corretor de PAT/PMT (e2proxy) embutido.
- **TVH Player** – servidores TVHeadend, sem proxy (o player liga-se diretamente ao servidor).

O mesmo projeto gera as duas apps (variantes `enigma2` e `tvheadend`); podem estar
instaladas ao mesmo tempo. Motor de vídeo: Media3 / ExoPlayer (+ FFmpeg para MP2/AC-3).

## Obter o APK sem instalar nada (GitHub Actions)

1. Cria uma conta gratuita em https://github.com (se ainda não tiveres).
2. Cria um repositório novo (pode ser **privado**): botão **New** › dá-lhe um
   nome (ex.: `e2player`) › **Create repository**.
3. Na página do repositório: **uploading an existing file** (ou *Add file › Upload files*)
   e arrasta **todo o conteúdo** desta pasta (incluindo a pasta `.github`).
   Carrega em **Commit changes**.
   - Se a pasta `.github` não aparecer (no Mac está oculta: Cmd+Shift+. no Finder),
     cria-a à mão: *Add file › Create new file*, nome
     `.github/workflows/build.yml`, e cola o conteúdo desse ficheiro.
4. Abre o separador **Actions**. A compilação arranca sozinha (~5 minutos).
   Se não arrancar: *Compilar APK › Run workflow*.
5. Quando ficar verde ✔, abre a execução e, em **Artifacts**, descarrega
   `e2player-apk` e/ou `tvhplayer-apk` (cada um é um .zip com o APK lá dentro).
6. Se ficar vermelho ✖, abre o passo "Compilar", copia as últimas linhas do erro
   e envia-mas.

## Instalar na Android TV

- Mais simples: instala a app **Downloader** (AFTVnews) na TV, ou **Send Files to TV**,
  e passa o APK; ou copia-o para uma pen USB e abre-o com um gestor de ficheiros.
- Em *Definições › Segurança / Apps › Fontes desconhecidas* permite a instalação
  para a app que estás a usar.
- Com adb: `adb connect IP_DA_TV` e `adb install app-debug.apk`.

Na primeira execução a app pede a box: usa **Procurar boxes na rede** (deteta
automaticamente as boxes com OpenWebif na tua rede local) ou escreve o IP à mão.
Também pede as portas e, se houver, o utilizador e a palavra-passe do OpenWebif. Podes alterar mais tarde em **⚙ Definições** (último
grupo da lista) ou com a tecla MENU.

## TVH Player (TVHeadend)

- Grupos = etiquetas de canais (channel tags) do TVHeadend; números = os do TVHeadend.
- EPG agora/seguinte e guia completo vêm do TVHeadend; ícones da imagecache.
- Definições: IP (ou **Procurar servidores TVHeadend na rede**), porta HTTP (9981),
  utilizador, palavra-passe e perfil de streaming (`pass` por omissão).
- Com palavra-passe, cada stream usa um *ticket* do TVHeadend (a palavra-passe não vai no URL).
  O utilizador precisa de permissão de streaming no TVHeadend.

## Comando

| Tecla | Ecrã inteiro | Lista |
|---|---|---|
| Cima / Baixo, CH+ / CH- | canal seguinte / anterior | mover / página |
| OK | abrir a lista | 1.º OK reproduz na pré-visualização, 2.º passa a ecrã inteiro |
| Esquerda / Direita | barra de informação | grupos ↔ canais |
| 0–9 | zapping por número | zapping por número |
| VERMELHO | favorito | favorito |
| VERDE | faixa de áudio | faixa de áudio |
| AMARELO | legendas | legendas |
| AZUL / GUIA | guia de programas | guia do canal selecionado |
| INFO | mostrar/esconder info | voltar ao vídeo |
| MENU | definições | definições |
| VOLTAR | esconde a info; 2× para sair | volta ao vídeo |

## Estrutura

- `core/TsFixer.java` – corretor de PAT/PMT (porte do e2proxy.py)
- `core/StreamProxy.java` – servidor HTTP local (127.0.0.1) que passa o stream pelo corretor
- `core/OpenWebif.java` – bouquets, EPG e picons via API XML do OpenWebif
- `core/BoxScanner.java` – deteção automática de boxes OpenWebif na rede local
- `core/ChannelSource.java` – interface comum das fontes; `core/E2Source.java` (Enigma2)
- `core/TvhClient.java` – fonte TVHeadend (API JSON, autenticação digest, tickets)
- `core/Json.java` – leitor JSON mínimo
- `core/Model.java` – grupos, numeração, favoritos, EPG agora/seguinte
- `MainActivity.java` – player, teclas, definições
- `TvView.java` – desenho da interface (listas, info, infobar, guia)
