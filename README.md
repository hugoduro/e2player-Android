# e2player para Android TV

Player de TV ao estilo MAG (Infomir) para boxes Enigma2, com o corretor de
PAT/PMT (e2proxy) embutido. Motor de vídeo: Media3 / ExoPlayer.

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
   `e2player-apk` (é um .zip com o `app-debug.apk` lá dentro).
6. Se ficar vermelho ✖, abre o passo "Compilar", copia as últimas linhas do erro
   e envia-mas.

## Instalar na Android TV

- Mais simples: instala a app **Downloader** (AFTVnews) na TV, ou **Send Files to TV**,
  e passa o APK; ou copia-o para uma pen USB e abre-o com um gestor de ficheiros.
- Em *Definições › Segurança / Apps › Fontes desconhecidas* permite a instalação
  para a app que estás a usar.
- Com adb: `adb connect IP_DA_TV` e `adb install app-debug.apk`.

Na primeira execução a app pede o IP da box, portas e (se houver) utilizador e
palavra-passe do OpenWebif. Podes alterar mais tarde em **⚙ Definições** (último
grupo da lista) ou com a tecla MENU.

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
- `core/Model.java` – grupos, numeração, favoritos, EPG agora/seguinte
- `MainActivity.java` – player, teclas, definições
- `TvView.java` – desenho da interface (listas, info, infobar, guia)
