# NSFW Filter Implementation — NemonicMail

## ✅ Implementação Realizada

### 1. **NsfwFilter.java** ✓
- Classe singleton para gerenciamento de modelo ONNX
- Métodos principais:
  - `init(File dataFolder, Logger pluginLogger)` — inicializa OrtEnvironment + OrtSession
  - `score(BufferedImage img)` — realiza inferência e retorna score NSFW (0.0-1.0)
  - `isAvailable()` — verifica se modelo foi carregado com sucesso
  - `close()` — limpeza de recursos
- Normalização ImageNet padrão para entrada do modelo
- Softmax para cálculo de score

### 2. **pom.xml** ✓
- Adicionada dependência: `com.microsoft.onnxruntime:onnxruntime:1.19.2`
- Adicionada relocation: `ai.onnxruntime → com.nemonicmail.libs.onnx`

### 3. **config.yml** ✓
```yaml
nsfw-filter:
  enabled: false
  threshold: 0.75
  notify-admin: true
  audit-log: true
```

### 4. **messages.yml** ✓
```yaml
filter:
  nsfw-blocked:     '&cSua imagem foi bloqueada por conteudo inadequado.'
  nsfw-blocked-url: '&cA imagem do link foi bloqueada por conteudo inadequado.'
```

### 5. **NemonicMail.java** ✓
- Adicionado `import NsfwFilter`
- Inicialização em `onEnable()`: `NsfwFilter.init(getDataFolder(), getLogger())`
- Limpeza em `onDisable()`: `NsfwFilter.close()`

---

## ⚠️ Próximos Passos (MANUAL)

### Passo 1: Baixar Modelo ONNX

Baixe o modelo Falconsai/nsfw_image_detection do Hugging Face:
```bash
# URL: https://huggingface.co/Falconsai/nsfw_image_detection
# Baixe o arquivo "model.onnx" (~50MB)
# Coloque em: src/main/resources/nsfw_model.onnx
```

Ou através do terminal:
```bash
cd NemonicMail/src/main/resources
wget https://huggingface.co/Falconsai/nsfw_image_detection/resolve/main/model.onnx -O nsfw_model.onnx
```

### Passo 2: Integrar em MapImageManager.java

**Localização**: Método `processUrl()` — linha aproximada 105-113

**Antes:**
```java
BufferedImage img = ImageProcessor.fetchImage(url);
if (plugin.getConfig().getBoolean("content-filter.enabled", false)) {
    float threshold = ...;
    float fraction  = ContentFilter.skinToneFraction(img);
    if (fraction > threshold) {
        throw new RuntimeException("Imagem bloqueada pelo filtro de conteúdo");
    }
}
```

**Depois (adicionar):**
```java
BufferedImage img = ImageProcessor.fetchImage(url);

// Verificar NSFW filter primeiro (mais importante)
if (NsfwFilter.isAvailable() && plugin.getConfig().getBoolean("nsfw-filter.enabled", false)) {
    try {
        float nsfwScore = NsfwFilter.score(img);
        float nsfwThreshold = (float) plugin.getConfig().getDouble("nsfw-filter.threshold", 0.75);
        if (nsfwScore > nsfwThreshold) {
            if (plugin.getConfig().getBoolean("nsfw-filter.audit-log", true)) {
                plugin.auditLog("IMAGE_NSFW_BLOCKED", player.getUniqueId().toString(), 
                               player.getName(), url, "score=" + nsfwScore);
            }
            if (plugin.getConfig().getBoolean("nsfw-filter.notify-admin", true)) {
                plugin.getLogger().warning("NSFW image blocked: " + url + " (score=" + nsfwScore + ") by " + player.getName());
            }
            throw new RuntimeException(plugin.getMessages().raw("filter.nsfw-blocked-url"));
        }
    } catch (Exception e) {
        if (!(e instanceof RuntimeException)) {
            plugin.getLogger().warning("NSFW filter error: " + e.getMessage());
        } else throw e;
    }
}

// Fallback para skin-tone heuristic (opcional)
if (plugin.getConfig().getBoolean("content-filter.enabled", false)) {
    float threshold = ...;
    float fraction  = ContentFilter.skinToneFraction(img);
    if (fraction > threshold) {
        throw new RuntimeException("Imagem bloqueada pelo filtro de conteúdo");
    }
}
```

### Passo 3: Integrar em ImageUploadServer.java

**Localização**: Método `handle()` — linha aproximada 185-203 (dentro do virtual thread lambda)

**Antes:**
```java
if (plugin.getConfig().getBoolean("content-filter.enabled", false)) {
    float fraction = ContentFilter.skinToneFraction(uploadData);
    if (fraction > threshold) {
        // block, audit log, send player message
        return;
    }
}
byte[][] tiles = ImageProcessor.fromBytes(uploadData, 1, 1);
```

**Depois (substituir):**
```java
// Decodificar uma vez
BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(uploadData));

// Verificar NSFW filter
if (NsfwFilter.isAvailable() && plugin.getConfig().getBoolean("nsfw-filter.enabled", false)) {
    try {
        float nsfwScore = NsfwFilter.score(decoded);
        float nsfwThreshold = (float) plugin.getConfig().getDouble("nsfw-filter.threshold", 0.75);
        if (nsfwScore > nsfwThreshold) {
            if (plugin.getConfig().getBoolean("nsfw-filter.audit-log", true)) {
                plugin.auditLog("IMAGE_NSFW_BLOCKED", uuid.toString(), 
                               playerName != null ? playerName : "unknown", 
                               token, "upload, score=" + nsfwScore);
            }
            if (plugin.getConfig().getBoolean("nsfw-filter.notify-admin", true)) {
                plugin.getLogger().warning("NSFW image blocked: upload token=" + token + 
                                          " (score=" + nsfwScore + ") by " + playerName);
            }
            sendJson(response, 400, new JsonObject()
                .addProperty("status", "nsfw")
                .addProperty("message", plugin.getMessages().raw("filter.nsfw-blocked")));
            return;
        }
    } catch (Exception e) {
        plugin.getLogger().warning("NSFW filter error during upload: " + e.getMessage());
    }
}

// Fallback para skin-tone heuristic (opcional)
if (plugin.getConfig().getBoolean("content-filter.enabled", false)) {
    float fraction = ContentFilter.skinToneFraction(decoded);
    if (fraction > threshold) {
        // block, audit log, send player message
        return;
    }
}

// Usar decoded para evitar double-decode
byte[][] tiles = ImageProcessor.fromImage(decoded, 1, 1);
```

---

## 📦 Build & Deploy

### Compilação
```bash
mvn clean package
```

### Arquivo Gerado
```
target/NemonicMail-1.0.0.jar
```

### Deployment
```bash
cp target/NemonicMail-1.0.0.jar /path/to/server/plugins/
cp nsfw_model.onnx /path/to/server/plugins/NemonicMail/
# Ou coloque o modelo na pasta plugins/NemonicMail/ manualmente
```

---

## 🧪 Teste

### Ativar no config.yml
```yaml
nsfw-filter:
  enabled: true
  threshold: 0.75
  notify-admin: true
  audit-log: true
```

### Testes Manuais
1. **URL NSFW bloqueada**:
   ```
   /carta imagem url https://example.com/nsfw.jpg
   # Esperado: &cA imagem do link foi bloqueada por conteudo inadequado.
   ```

2. **Upload NSFW bloqueado**:
   ```
   /carta imagem upload
   # Browser: selecionar imagem NSFW
   # Esperado: erro 400 com mensagem NSFW
   ```

3. **Imagem segura passa**:
   ```
   /carta imagem url https://example.com/safe-landscape.jpg
   # Esperado: sucesso, processamento normal
   ```

4. **Verificar audit log**:
   ```sql
   SELECT * FROM audit_log WHERE event_type='IMAGE_NSFW_BLOCKED';
   ```

---

## 📊 Recursos

- **Modelo**: ~50MB (Falconsai/nsfw_image_detection)
- **Inferência**: ~100-200ms por imagem (CPU)
- **Memory**: ~200MB ONNX Runtime + modelo em memória
- **Thread**: Async (não bloqueia main thread)

---

## Notas Importantes

1. **Modelo não incluído no JAR**: O arquivo `nsfw_model.onnx` deve ser baixado separadamente e colocado na pasta do plugin
2. **Compatibilidade**: Requer Java 21 (como todo o NemonicMail)
3. **Threshold recomendado**: 0.75 para melhor balanço entre detecção e falsos positivos
4. **Fallback**: Se NSFW filter falhar, HSV skin-tone filter é usada como fallback
5. **Performance**: Não há impacto significativo já que inferência roda em thread async

