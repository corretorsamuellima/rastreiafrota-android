# RastreiaFrota Áudio Plus 1.3.0

Aplicativo Android nativo em Kotlin para rastreamento veicular, gravação de segurança autorizada e solicitações/agendamentos de áudio enviados pelo Painel CarroSeguro 2.5.

## Novidades da versão 1.3.0

- Botão para atualizar a URL do servidor e reconfigurar o aparelho.
- HTTPS obrigatório em produção e nova ativação após a troca de servidor.
- Pausar e continuar a gravação pelo aplicativo ou pela notificação.
- Estado da pausa persistido e exibido corretamente na interface.
- Notificações obrigatórias, silenciosas e de baixa prioridade para os serviços em segundo plano.
- Fila autenticada de comandos remotos para GPS, áudio, sincronização e configurações.
- Confirmação ao painel do resultado executado ou da falha encontrada no celular.

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
3. No Windows, execute `gradlew.bat assembleDebug`.
4. O APK debug será gerado em `app/build/outputs/apk/debug/app-debug.apk`.
5. Para release, copie `keystore.properties.example` para `keystore.properties`, configure sua chave e execute `gradlew.bat assembleRelease`.

## Versão entregue

- Aplicativo: RastreiaFrota Áudio Plus
- Package ID: `com.rastreiafrota.app.audio.plus`
- Versão: `1.3.0`
- Version code: `7`
- Android mínimo: 8.0 (API 26)
- Target SDK: 34
- Build debug/LAN permite HTTP para teste local com XAMPP.
- Build release mantém HTTPS obrigatório.

## Arquitetura

MVVM leve + Repository, Room, Retrofit/OkHttp, WorkManager, Foreground Service, DataStore e Android Keystore/EncryptedSharedPreferences.
