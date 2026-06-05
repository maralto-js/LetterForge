package com.nemonicmail.image;

import java.awt.image.BufferedImage;

/**
 * SPI de pontuação NSFW. O core (NemonicMail) NÃO embute nenhum motor de ML — ele apenas
 * declara este ponto de extensão. Um addon (ex.: NemonicMail-NSFW-Model, premium) carrega
 * o modelo ONNX em seu próprio classloader e registra uma implementação via
 * {@link NsfwFilter#register(NsfwScorer)}.
 *
 * <p>Mantém o jar do core leve (sem ONNX Runtime nem bibliotecas nativas) e isola a
 * dependência pesada no addon, que é opcional.</p>
 */
@FunctionalInterface
public interface NsfwScorer {

    /**
     * @param image imagem já decodificada
     * @return probabilidade NSFW no intervalo [0.0, 1.0] (0 = seguro, 1 = NSFW)
     * @throws Exception se a inferência falhar
     */
    float score(BufferedImage image) throws Exception;

    /** Nome legível do motor, para logs. */
    default String engineName() { return getClass().getSimpleName(); }

    /** Libera recursos nativos do motor. No-op por padrão. */
    default void close() {}
}
