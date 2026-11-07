package com.tradinglab.portfolio;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("trading")
record PortfolioProperties(BigDecimal startingCash) {
}
