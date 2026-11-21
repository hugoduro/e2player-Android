# e2player para Android TV

Player Android TV para streaming de boxes Enigma2, com o corretor de
PAT/PMT (e2proxy) embutido. Motor de vídeo: Media3 / ExoPlayer.

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

<img width="1920" height="1079" alt="channel_list_with_preview" src="https://github.com/user-attachments/assets/722a33cc-27cb-45d5-9067-de2902c247c4" />

<img width="1920" height="1079" alt="channel_streaming" src="https://github.com/user-attachments/assets/0ef1e0be-34e3-4d27-9923-dad6441b05db" />
