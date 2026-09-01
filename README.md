# RastreiaFrota Áudio Plus 1.8.1

Aplicativo Android nativo em Kotlin para rastreamento veicular contínuo e autorizado, gravação de segurança autorizada e solicitações/agendamentos enviados pelo Painel CarroSeguro.

## Novidades da versão 1.8.1

- Rastreamento de alta precisão com atualização alvo de 1 segundo durante movimento.
- Distância mínima dinâmica: até 2 metros em deslocamento lento, 5 metros em velocidade urbana e 10 metros em velocidade alta.
- Pontos gravados no máximo a cada 2 segundos durante o movimento e em mudanças relevantes de direção, preservando curvas e caminhadas.
- O primeiro ponto só vira âncora com precisão de até 15 metros; os demais pontos do trajeto exigem até 25 metros ou o limite menor configurado pelo painel.
- Oscilações dentro da margem combinada de erro das duas coordenadas são rejeitadas, mesmo que o GPS calcule uma falsa velocidade.
- A velocidade só confirma movimento quando continua positiva após descontar a incerteza informada pelo GNSS.
- Suavização adaptativa mais forte em caminhada e mais leve em veículos reduz zigue-zagues sem cortar curvas.
- A última âncora é restaurada depois de reiniciar o serviço, evitando uma linha falsa no mesmo percurso.
- Watchdog reinicia a captura se o Android parar de entregar posições ao serviço.
- Pontos sem precisão suficiente não entram no traçado; o aplicativo continua aguardando uma coordenada confiável sem inventar deslocamento.
- Diagnóstico separado para última captura GPS, último ponto armazenado, fila pendente, sincronização e erro de rastreamento.
- O aplicativo reinicia o serviço ao ser aberto quando o rastreamento já está habilitado e as permissões estão prontas.
- Pontos rejeitados pela API permanecem na fila local para nova tentativa e investigação; não são marcados como sincronizados.
- Cada percurso recebe uma sessão própria e cada posição recebe um número sequencial.
- O serviço processa todos os pontos entregues pelo GPS, e não somente o último de cada retorno.
- Filtros contra posição antiga, baixa precisão, repetição e salto impossível de GPS.
- Prévia vetorial do trajeto no próprio aplicativo, inclusive sem internet e sem chave externa de mapa.
- Resumo local com distância, pontos, duração, velocidade e qualidade do sinal.
- Migrações Room preservam os pontos pendentes das versões anteriores.
- Sirene antifurto acionável pelo painel, com notificação visível, botão local para parar e desligamento automático de segurança.

### Recursos preservados da versão 1.4.2

- Botão para atualizar a URL do servidor e reconfigurar o aparelho.
- HTTPS obrigatório em produção e nova ativação após a troca de servidor.
- Pausar e continuar a gravação pelo aplicativo ou pela notificação.
- Estado da pausa persistido e exibido corretamente na interface.
- Notificações obrigatórias, silenciosas e de baixa prioridade para os serviços em segundo plano.
- Fila autenticada de comandos remotos para GPS, áudio, sincronização e configurações.
- Confirmação ao painel do resultado executado ou da falha encontrada no celular.
- Push FCM opcional para acordar imediatamente a fila segura de comandos.
- Registro/rotação do token push pela API HMAC e fallback de polling quando o push não está configurado.
- Compatibilidade com agendamentos GPS criados no painel 2.6.
- Assistente de preparação para localização precisa, localização o tempo todo, notificações, GPS, internet e liberação de bateria.
- Diagnóstico do Firebase e do último registro do token push, sem exibir o token.
- Envio ao painel do estado real das permissões essenciais do celular.
- Recebimento da configuração pública Firebase diretamente do Painel Master na ativação e nas atualizações remotas.
- Troca ou desativação do projeto Firebase sem precisar recompilar o APK.

## Comportamento das solicitações

- O painel pode enviar uma solicitação imediata ou agendar data, horário, duração e recorrência.
- O celular mostra notificação de alta prioridade e uma tela com empresa, veículo, motivo e duração.
- O microfone nunca é iniciado silenciosamente pelo comando do painel.
- A gravação começa somente após o usuário tocar em **Autorizar e iniciar**.
- Depois de autorizada, continua em segundo plano e com a tela apagada, mantendo notificação visível.
- Sem internet, os blocos ficam na fila local e são sincronizados posteriormente.

## Compilar

1. Abra esta pasta no Android Studio com JDK 17 ou superior.
2. Para produção, ajuste `BASE_URL` do build `release` em `app/build.gradle.kts` para um domínio HTTPS.
3. Para push em tempo real, configure o Firebase em **Master → Configurações**. As propriedades Gradle `FIREBASE_*` continuam disponíveis somente como fallback de compilação.
4. No Windows, execute `gradlew.bat assembleDebug`.
5. O APK debug será gerado em `app/build/outputs/apk/debug/app-debug.apk`.
6. Para release, copie `keystore.properties.example` para `keystore.properties`, configure sua chave e execute `gradlew.bat assembleRelease`.

## Versão entregue

- Aplicativo: RastreiaFrota Áudio Plus
- Package ID: `com.rastreiafrota.app.audio.plus`
- Versão: `1.8.1`
- Version code: `15`
- Android mínimo: 8.0 (API 26)
- Target SDK: 34
- Build debug/LAN permite HTTP para teste local com XAMPP.
- Build release mantém HTTPS obrigatório.

## Arquitetura

MVVM leve + Repository, Room, Retrofit/OkHttp, WorkManager, Foreground Service, DataStore e Android Keystore/EncryptedSharedPreferences.
