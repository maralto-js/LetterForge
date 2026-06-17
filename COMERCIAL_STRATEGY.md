# NemonicMail — Estratégia de Comercialização

## 📦 Modelo de Distribuição: Addon Separado

```
Produto 1: NemonicMail-Core (FREE ou PAID)
└─ Plugin base completo
   ├─ Sistema de cartas
   ├─ Imagens em mapas
   ├─ Upload HTTP
   └─ Suporte a NSFW (com fallback)

Produto 2: NemonicMail-NSFW-Model (PREMIUM ADD-ON)
└─ Modelo ONNX Falconsai (~50MB)
   ├─ Integração automática
   ├─ Ativa detecção avançada
   └─ Garante segurança total
```

---

## 🏗️ Arquitetura Técnica

### **NemonicMail-Core**
- Funciona 100% sem modelo
- NSFW Filter desabilitado por padrão
- Se addon não existir: fallback para HSV skin-tone
- Admin vê aviso: "NSFW Model addon não encontrado"

### **NemonicMail-NSFW-Model** (Addon)
- Arquivo `nsfw_model.onnx` compacto
- Auto-registra em `NsfwFilter`
- Ativa automaticamente se core carregado
- Sem interferência se não instalado

---

## 🔧 Implementação Técnica

### Estrutura Maven

**NemonicMail-Core** (pom.xml):
```xml
<!-- REMOVER: dependência onnxruntime -->
<!-- NsfwFilter fica como "optional" -->
<!-- Se addon existir, usa; se não, usa fallback -->
```

**NemonicMail-NSFW-Model** (novo pom.xml):
```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.nemonicmail</groupId>
    <artifactId>nemonicmail-nsfw-model</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <dependencies>
        <!-- Dependência do core para hooks -->
        <dependency>
            <groupId>com.nemonicmail</groupId>
            <artifactId>NemonicMail</artifactId>
            <version>1.0.0</version>
            <scope>provided</scope>
        </dependency>

        <!-- ONNX Runtime (shaded) -->
        <dependency>
            <groupId>com.microsoft.onnxruntime</groupId>
            <artifactId>onnxruntime</artifactId>
            <version>1.19.2</version>
            <scope>compile</scope>
        </dependency>
    </dependencies>
</project>
```

---

## 📝 Código: Core sem ONNX Dependency

### **NemonicMail.java** — Removido NsfwFilter init obrigatório

```java
@Override
public void onEnable() {
    // ... resto do código ...

    // NSFW Model é opcional — só ativa se addon carregado
    if (isNsfwModelAvailable()) {
        getLogger().info("[NemonicMail] NSFW Model addon detectado — ativando...");
        // Addon já se registrou, NsfwFilter está pronto
    } else {
        getLogger().info("[NemonicMail] NSFW Model addon não instalado.");
        getLogger().info("[NemonicMail] Para ativar detecção avançada, adquira o addon.");
    }

    // ... resto do código ...
}

private boolean isNsfwModelAvailable() {
    try {
        Class.forName("com.nemonicmail.image.NsfwModelAddon");
        return true;
    } catch (ClassNotFoundException e) {
        return false;
    }
}
```

### **NsfwModelAddon.java** — Novo arquivo no addon

```java
package com.nemonicmail.image;

import org.bukkit.plugin.java.JavaPlugin;

public class NsfwModelAddon extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("[NSFW Model] Inicializando modelo...");
        try {
            NsfwFilter.init(getDataFolder(), getLogger());
            getLogger().info("[NSFW Model] ✓ Modelo carregado com sucesso!");
            getLogger().info("[NSFW Model] Detecção NSFW ativada para NemonicMail.");
        } catch (Exception e) {
            getLogger().severe("[NSFW Model] ✗ Falha ao carregar: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        NsfwFilter.close();
        getLogger().info("[NSFW Model] Desativado.");
    }
}
```

### **plugin.yml** — Addon

```yaml
name: NemonicMail-NSFW-Model
version: 1.0.0
main: com.nemonicmail.image.NsfwModelAddon
description: Modelo ONNX de detecção NSFW para NemonicMail
authors: [NemonicRP]
depend: [NemonicMail]
api-version: '1.21'
```

---

## 📦 Build & Distribuição

### Build do Core (sem ONNX)

```bash
cd NemonicMail
mvn clean package
# Resultado: target/NemonicMail-1.0.0.jar (~5MB)
```

### Build do Addon (com modelo)

```bash
mkdir NemonicMail-NSFW-Model
cd NemonicMail-NSFW-Model

# Copiar pom.xml (addon)
# Copiar src/ com NsfwFilter.java e NsfwModelAddon.java
# Copiar src/main/resources/nsfw_model.onnx

mvn clean package
# Resultado: target/nemonicmail-nsfw-model-1.0.0.jar (~55MB)
```

---

## 🎯 Pacotes de Venda

### **Pacote 1: NemonicMail Free**
```
- NemonicMail-1.0.0.jar (Core)
- Recursos:
  ✓ Sistema de cartas completo
  ✓ Imagens em mapas
  ✓ Upload HTTP
  ✓ Filtro skin-tone básico (HSV)
  ✗ Detecção NSFW avançada
- Preço: Gratuito
```

### **Pacote 2: NemonicMail Premium**
```
- NemonicMail-1.0.0.jar (Core)
- nemonicmail-nsfw-model-1.0.0.jar (Addon)
- Recursos:
  ✓ Sistema completo
  ✓ Detecção NSFW via ONNX (acurácia 95%+)
  ✓ Audit log de bloqueios
  ✓ Notificações admin
- Preço: $9.99 (exemplo) ou incluído em pacote maior
```

### **Pacote 3: Servidor Completo (Bundle)**
```
- Core + Addon + Customização
- Suporte direto
- Preço: $29.99+ (negociável)
```

---

## 🔐 Proteção Comercial

### Licença & Anti-Pirataria

**Opção 1: Simple — Rename Check**
```java
// Em NemonicMail.onEnable():
if (!getFile().getName().equals("NemonicMail-1.0.0.jar")) {
    getLogger().warning("⚠️ JAR renamed — licença inválida!");
    getServer().getPluginManager().disablePlugin(this);
}
```

**Opção 2: Advanced — UUID Check**
```java
// Salvar UUID da instalação primeira vez
// Comparar em logins subsequentes
// Se diferente: aviso ou desabilitar
```

**Opção 3: Online License (Profissional)**
```java
// API remota verifica licença
// Requer conectividade
// Mais seguro, mais complexo
```

---

## 📊 Monetização Recomendada

| Canal | Modelo | Preço |
|-------|--------|-------|
| **Spigot/Bukkit** | Free Core + Premium Addon | $0 / $9.99 |
| **GitHub** | Open-source Free | $0 |
| **Discord/Website** | Premium + Support | $19.99/mês |
| **Custom Servers** | Enterprise License | $99+ |

---

## 🚀 Roadmap Comercial

### **Fase 1: Lançamento** (Semana 1-2)
- [ ] Separar Core e Addon
- [ ] Build ambos
- [ ] Tester em servidor próprio
- [ ] Lançar versão 1.0.0

### **Fase 2: Marketing** (Semana 3-4)
- [ ] Publicar no Spigot (Free com opção Premium)
- [ ] GitHub Actions para release automático
- [ ] Documentação em português/inglês
- [ ] Vídeo de demonstração

### **Fase 3: Monetização** (Semana 5+)
- [ ] Implementar proteção de licença
- [ ] Criar página de venda
- [ ] Suporte a clientes premium
- [ ] Coletar feedback para v1.1

---

## 💡 Dicas Profissionais

1. **Versionamento**: Core e Addon em sincronização
   ```
   NemonicMail 1.0.0 ← compatível com →  NSFW-Model 1.0.0
   NemonicMail 1.1.0 ← compatível com →  NSFW-Model 1.1.0
   ```

2. **Documentação**: Deixar claro o que é free vs premium
   ```
   Plugin Página:
   - Free: Features A, B, C
   - Premium Addon: Feature D (NSFW Detection)
   ```

3. **Support**: Ofereça suporte técnico para paying customers
   ```
   Discord: suporte@nemonicmail.com
   Ticket System: Pago vs Grátis
   ```

4. **Atualizações**: Core recebe updates, Addon vira pago depois
   ```
   v1.0: Ambos
   v1.1: Core free, Addon pago
   v2.0: Possível novo addon (ex: Analytics)
   ```

---

## 📋 Checklist Final

- [ ] Remover `onnxruntime` dependency do Core pom.xml
- [ ] Modificar `NsfwFilter.init()` para ser chamado pelo Addon
- [ ] Criar projeto separado `NemonicMail-NSFW-Model`
- [ ] Criar `NsfwModelAddon.java` em novo projeto
- [ ] Criar `plugin.yml` do addon
- [ ] Build ambos JARs
- [ ] Testar com e sem addon
- [ ] Documentar no README
- [ ] Publicar versão comercial

