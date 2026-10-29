# Gravador Tela + Câmera

App Android simples que grava **a tela** e **a câmera traseira** ao mesmo tempo, com áudio do microfone.

- Android 11 ou superior (feito para funcionar no Android 17).
- Os vídeos ficam em **Filmes/GravadorTelaCamera** (aparecem na galeria).

## Como usar

1. Instale o APK (aba **Releases** do GitHub, ou **Actions → Build APK → Artifacts**).
2. Abra o app, toque em **Iniciar gravação**, aceite as permissões e a autorização de gravação de tela.
3. O app fecha sozinho e aparece uma **contagem regressiva de 3 segundos** (com bipe). Quando chega a zero, a gravação começa.
4. Para parar: toque na **notificação** ou use o bloco **Gravar** nas configurações rápidas.

Não existe botão na tela durante a gravação, então nenhum botão do app aparece no vídeo. Só evite abrir a barra de notificações durante a gravação, senão ela aparece no vídeo.

### Dois modos (caixa de seleção no app)

- **Vídeo único (padrão):** a câmera traseira aparece numa janelinha no canto inferior direito da tela e é gravada junto com ela, em um arquivo só, `gravacao_AAAAMMDD_HHMMSS.mp4`. A janelinha já aparece durante a contagem, para você enquadrar. Neste modo a autorização de gravação pede "tela inteira".
- **Dois arquivos:** `tela_...mp4` e `camera_...mp4`, cada um com áudio.

### Permissão "Exibir sobre outros apps"

A contagem e a janelinha da câmera usam essa permissão. Em geral o Android a libera sozinho enquanto a gravação de tela está ativa. Se o app avisar que não tem a permissão, ative em **Configurações → Apps → Gravador Tela + Câmera → Exibir sobre outros apps** (o app grava em 2 arquivos enquanto isso).

### Bloco nas configurações rápidas

Puxe o painel de configurações rápidas, toque no lápis (editar) e arraste o bloco **Gravar** para o painel.

## Dicas e limites

- O Android pede a autorização de gravação de tela toda vez que você inicia. Não dá para evitar.
- Gire o celular para a posição desejada **antes** de iniciar: a orientação do vídeo fica fixa durante a gravação.
- Em Xiaomi, Samsung e similares, desative a otimização de bateria para o app, senão o sistema pode encerrar a gravação.
- Este APK é de depuração (assinado com a chave de debug), suficiente para instalar e usar.

## Compilar

O GitHub Actions compila a cada push na branch `main` (`.github/workflows/build.yml`) e publica o APK como release. Também dá para rodar em **Actions → Build APK → Run workflow**.

Localmente: abra a pasta no Android Studio, ou rode `gradle assembleDebug` (JDK 17 e Android SDK 34).
