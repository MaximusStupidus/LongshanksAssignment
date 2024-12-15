package org.example;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.example.SimpleMovingAverage.calculateMaxDrawdown;

public class MeanReversionEMAHybrid {

    private static final double INITIAL_CAPITAL = 1_000_000.0;
    private static final double RISK_PER_TRADE = 0.005;
    private static final int ADX_THRESHOLD = 20;

    private static BigDecimal calculateEMA(List<StockData> stockData, int endIndex, int window) {
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (window + 1));

        BigDecimal ema = stockData.stream()
                .map(StockData::getAdjClose)
                .skip(endIndex - window)
                .limit(window)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MathContext.DECIMAL128);

        for (int i = endIndex - window + 1; i <= endIndex; i++) {
            BigDecimal price = stockData.get(i).getAdjClose();
            ema = price.multiply(multiplier).add(ema.multiply(BigDecimal.ONE.subtract(multiplier)));
        }

        return ema;
    }

    private static BigDecimal calculateRSI(List<StockData> stockData, int endIndex, int period) {
        BigDecimal gainSum = BigDecimal.ZERO;
        BigDecimal lossSum = BigDecimal.ZERO;

        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            BigDecimal change = stockData.get(i).getAdjClose().subtract(stockData.get(i - 1).getAdjClose());
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                gainSum = gainSum.add(change);
            } else {
                lossSum = lossSum.add(change.abs());
            }
        }

        if (lossSum.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100); // Max RSI if no losses
        }

        BigDecimal avgGain = gainSum.divide(BigDecimal.valueOf(period), MathContext.DECIMAL128);
        BigDecimal avgLoss = lossSum.divide(BigDecimal.valueOf(period), MathContext.DECIMAL128);

        BigDecimal rs = avgGain.divide(avgLoss, MathContext.DECIMAL128);
        return BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(100).divide(rs.add(BigDecimal.ONE), MathContext.DECIMAL128));
    }

    private static BigDecimal calculateMACD(List<StockData> stockData, int endIndex, int shortWindow, int longWindow) {
        BigDecimal shortEMA = calculateEMA(stockData, endIndex, shortWindow);
        BigDecimal longEMA = calculateEMA(stockData, endIndex, longWindow);
        return shortEMA.subtract(longEMA);
    }

    public static BigDecimal calculateATR(List<StockData> stockData, int endIndex, int period) {
        BigDecimal atrSum = BigDecimal.ZERO;

        for (int i = endIndex - period + 1; i <= endIndex; i++) {
            BigDecimal highLow = stockData.get(i).getHigh().subtract(stockData.get(i).getLow());
            BigDecimal highPrevClose = stockData.get(i).getHigh().subtract(stockData.get(i - 1).getAdjClose()).abs();
            BigDecimal lowPrevClose = stockData.get(i).getLow().subtract(stockData.get(i - 1).getAdjClose()).abs();

            BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);
            atrSum = atrSum.add(trueRange);
        }

        return atrSum.divide(BigDecimal.valueOf(period), MathContext.DECIMAL128);
    }

    public static void simulateHybridStrategy(int shortWindow, int longWindow, int adxPeriod, int rsiPeriod) {
        StockDataManager dataManager = new StockDataManager();
        dataManager.loadHistoricalDataFromCSV("stock_data/consolidated_stock_data.csv");
        List<String> stocks = dataManager.getStocks();

        BigDecimal cash = BigDecimal.valueOf(INITIAL_CAPITAL);
        Map<String, Long> portfolio = new HashMap<>();
        Map<String, BigDecimal> closingPrice = new HashMap<>();
        List<BigDecimal> dailyReturns = new ArrayList<>();
        List<BigDecimal> portfolioValues = new ArrayList<>();
        BigDecimal portfolioValue = cash;

        for (int i = Math.max(longWindow, adxPeriod); i < dataManager.getHistoricalData(stocks.get(0)).size(); i++) {
            Map<String, Integer> signals = new HashMap<>();

            for (String stock : stocks) {
                List<StockData> stockData = dataManager.getHistoricalData(stock);
                closingPrice.put(stock, stockData.get(i).getAdjClose());

                BigDecimal shortEMA = calculateEMA(stockData, i, shortWindow);
                BigDecimal longEMA = calculateEMA(stockData, i, longWindow);
                BigDecimal adx = calculateATR(stockData, i, adxPeriod);
                BigDecimal rsi = calculateRSI(stockData, i, rsiPeriod);
                BigDecimal macd = calculateMACD(stockData, i, 12, 26); // Example MACD parameters

                if (adx.compareTo(BigDecimal.valueOf(ADX_THRESHOLD)) > 0) {
                    // Trending Market: Use Momentum Strategy
                    if (shortEMA.compareTo(longEMA) > 0 && macd.compareTo(BigDecimal.ZERO) > 0) {
                        signals.put(stock, 1); // Strong Buy
                    } else if (shortEMA.compareTo(longEMA) < 0 && macd.compareTo(BigDecimal.ZERO) < 0) {
                        signals.put(stock, -1); // Strong Sell
                    } else {
                        signals.put(stock, 0); // Hold
                    }
                } else {
                    // Non-Trending Market: Use Mean Reversion Strategy
                    if (rsi.compareTo(BigDecimal.valueOf(30)) < 0) {
                        signals.put(stock, 1); // Buy
                    } else if (rsi.compareTo(BigDecimal.valueOf(70)) > 0) {
                        signals.put(stock, -1); // Sell
                    } else {
                        signals.put(stock, 0); // Hold
                    }
                }
            }

            for (String stock : stocks) {
                BigDecimal atr = calculateATR(dataManager.getHistoricalData(stock), i, 14);
                BigDecimal price = closingPrice.get(stock);
                BigDecimal riskAmount = portfolioValue.multiply(BigDecimal.valueOf(RISK_PER_TRADE));
                BigDecimal stopLossDistance = atr.multiply(BigDecimal.valueOf(3));
                long maxSharesBasedOnRisk = riskAmount.divide(stopLossDistance, MathContext.DECIMAL128).longValue();

                if (signals.get(stock) == 1) {
                    long affordableShares = cash.divide(price, MathContext.DECIMAL128).longValue();
                    long sharesToBuy = Math.min(maxSharesBasedOnRisk, affordableShares);

                    if (sharesToBuy > 0) {
                        cash = cash.subtract(price.multiply(BigDecimal.valueOf(sharesToBuy)));
                        portfolio.put(stock, portfolio.getOrDefault(stock, 0L) + sharesToBuy);
                    }
                } else if (signals.get(stock) == -1) {
                    long currentHoldings = portfolio.getOrDefault(stock, 0L);
                    if (currentHoldings > 0) {
                        cash = cash.add(price.multiply(BigDecimal.valueOf(currentHoldings)));
                        portfolio.put(stock, 0L);
                    }
                }
            }

            BigDecimal newPortfolioValue = cash;
            for (String stock : stocks) {
                BigDecimal holdingValue = BigDecimal.valueOf(portfolio.getOrDefault(stock, 0L)).multiply(closingPrice.get(stock));
                newPortfolioValue = newPortfolioValue.add(holdingValue);
            }

            BigDecimal dailyReturn = newPortfolioValue.subtract(portfolioValue).divide(portfolioValue, MathContext.DECIMAL128);
            dailyReturns.add(dailyReturn);
            portfolioValue = newPortfolioValue;
            portfolioValues.add(portfolioValue);
        }

        // Calculate performance metrics
        BigDecimal averageReturn = dailyReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal variance = dailyReturns.stream()
                .map(r -> r.subtract(averageReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal standardDeviation = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal sharpeRatio = averageReturn.divide(standardDeviation, MathContext.DECIMAL128);
        BigDecimal annualizedSharpeRatio = sharpeRatio.multiply(BigDecimal.valueOf(Math.sqrt(252)));

        System.out.printf("Backtest Results (Hybrid Strategy with ATR-Based Sizing):%n");
        System.out.printf("Initial Capital: $%.6f%n", INITIAL_CAPITAL);
        System.out.printf("Final Capital: $%.6f%n", portfolioValue);
        System.out.printf("Sharpe Ratio: %.6f%n", sharpeRatio);
        System.out.printf("Annualized Sharpe Ratio: %.6f%n", annualizedSharpeRatio);
        System.out.printf("Maximum Drawdown: %.6f%n", calculateMaxDrawdown(portfolioValues.stream().map(BigDecimal::doubleValue).toList()));
    }

    public static void main(String[] args) {
        simulateHybridStrategy(10, 50, 14, 14); // Example parameters
    }
}
