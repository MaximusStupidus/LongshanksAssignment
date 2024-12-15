package org.example;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.example.SimpleMovingAverage.calculateMaxDrawdown;

public class MeanReversionWithRSI {

    private static final double INITIAL_CAPITAL = 1_000_000.0;
    private static final double RISK_PER_TRADE = 0.005;

    private static BigDecimal calculateMean(List<StockData> stockData, int endIndex, int window) {
        return stockData.stream()
                .map(StockData::getAdjClose)
                .skip(endIndex - window)
                .limit(window)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MathContext.DECIMAL128);
    }

    private static BigDecimal calculateStandardDeviation(List<StockData> stockData, int endIndex, int window, BigDecimal mean) {
        BigDecimal variance = stockData.stream()
                .map(StockData::getAdjClose)
                .skip(endIndex - window)
                .limit(window)
                .map(price -> price.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MathContext.DECIMAL128);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private static BigDecimal calculateRSI(List<StockData> stockData, int endIndex, int period) {
        if (endIndex < period) {
            return BigDecimal.valueOf(50); // Neutral RSI if not enough data
        }

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

    private static Map<String, Object> simulate(int bollingerWindow, int rsiPeriod) {
        StockDataManager dataManager = new StockDataManager();
        dataManager.loadHistoricalDataFromCSV("stock_data/consolidated_stock_data.csv");
        List<String> stocks = dataManager.getStocks();

        BigDecimal cash = BigDecimal.valueOf(INITIAL_CAPITAL);
        Map<String, Long> portfolio = new HashMap<>();
        Map<String, BigDecimal> closingPrice = new HashMap<>();
        List<BigDecimal> dailyReturns = new ArrayList<>();
        List<BigDecimal> portfolioValues = new ArrayList<>();
        BigDecimal portfolioValue = cash;

        for (int i = bollingerWindow; i < dataManager.getHistoricalData(stocks.get(0)).size(); i++) {
            Map<String, Integer> signals = new HashMap<>();

            // Generate signals using Bollinger Bands and RSI
            for (String stock : stocks) {
                List<StockData> stockData = dataManager.getHistoricalData(stock);
                closingPrice.put(stock, stockData.get(i).getAdjClose());

                BigDecimal mean = calculateMean(stockData, i, bollingerWindow);
                BigDecimal stdDev = calculateStandardDeviation(stockData, i, bollingerWindow, mean);
                BigDecimal price = stockData.get(i).getAdjClose();

                BigDecimal upperBand = mean.add(stdDev.multiply(BigDecimal.valueOf(2)));
                BigDecimal lowerBand = mean.subtract(stdDev.multiply(BigDecimal.valueOf(2)));
                BigDecimal rsi = calculateRSI(stockData, i, rsiPeriod);

                if (price.compareTo(lowerBand) <= 0 && rsi.compareTo(BigDecimal.valueOf(30)) < 0) {
                    signals.put(stock, 1); // Buy signal
                } else if (price.compareTo(upperBand) >= 0 && rsi.compareTo(BigDecimal.valueOf(70)) > 0) {
                    signals.put(stock, -1); // Sell signal
                } else {
                    signals.put(stock, 0); // Hold
                }
            }

            // Adjust positions based on signals
            for (String stock : stocks) {
                BigDecimal price = closingPrice.get(stock);
                BigDecimal riskAmount = portfolioValue.multiply(BigDecimal.valueOf(RISK_PER_TRADE));

                if (signals.get(stock) == 1) {
                    long affordableShares = cash.divideToIntegralValue(price).longValue();
                    if (affordableShares > 0) {
                        cash = cash.subtract(price.multiply(BigDecimal.valueOf(affordableShares)));
                        portfolio.put(stock, portfolio.getOrDefault(stock, 0L) + affordableShares);
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
                BigDecimal currentHolding = BigDecimal.valueOf(portfolio.getOrDefault(stock, 0L));
                newPortfolioValue = newPortfolioValue.add(currentHolding.multiply(closingPrice.get(stock)));
            }

            BigDecimal dailyReturn = newPortfolioValue.subtract(portfolioValue).divide(portfolioValue, MathContext.DECIMAL128);
            dailyReturns.add(dailyReturn);
            portfolioValue = newPortfolioValue;
            portfolioValues.add(portfolioValue);
        }

        // Calculate Sharpe ratio
        BigDecimal averageReturn = dailyReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal variance = dailyReturns.stream()
                .map(r -> r.subtract(averageReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal standardDeviation = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal sharpeRatio = averageReturn.divide(standardDeviation, MathContext.DECIMAL128);

        double maxDrawdown = calculateMaxDrawdown(portfolioValues.stream().map(BigDecimal::doubleValue).toList());

        Map<String, Object> result = new HashMap<>();
        result.put("FinalCapital", portfolioValue.doubleValue());
        result.put("MaxDrawdown", maxDrawdown);
        result.put("SharpeRatio", sharpeRatio.doubleValue() * Math.sqrt(252));
        return result;
    }

    public static void main(String[] args) {
        int minBollingerWindow = 10;
        int maxBollingerWindow = 50;
        int stepBollingerWindow = 5;
        int minRSIPeriod = 10;
        int maxRSIPeriod = 30;
        int stepRSIPeriod = 5;

        List<Map<String, Object>> results = new ArrayList<>();

        for (int bollingerWindow = minBollingerWindow; bollingerWindow <= maxBollingerWindow; bollingerWindow += stepBollingerWindow) {
            for (int rsiPeriod = minRSIPeriod; rsiPeriod <= maxRSIPeriod; rsiPeriod += stepRSIPeriod) {
                System.out.printf("Testing combination: Bollinger Window = %d, RSI Period = %d%n", bollingerWindow, rsiPeriod);
                Map<String, Object> result = simulate(bollingerWindow, rsiPeriod);
                result.put("BollingerWindow", bollingerWindow);
                result.put("RSIPeriod", rsiPeriod);
                results.add(result);
            }
        }

        // Save results to CSV
        try (FileWriter writer = new FileWriter("mean_reversion_rsi_results.csv")) {
            writer.append("BollingerWindow,RSIPeriod,FinalCapital,MaxDrawdown,SharpeRatio\n");
            for (Map<String, Object> result : results) {
                writer.append(String.format("%d,%d,%.2f,%.6f,%.6f\n",
                        result.get("BollingerWindow"),
                        result.get("RSIPeriod"),
                        result.get("FinalCapital"),
                        result.get("MaxDrawdown"),
                        result.get("SharpeRatio")));
            }
            System.out.println("Results saved to mean_reversion_rsi_results.csv");
        } catch (IOException e) {
            System.err.println("Error writing to CSV: " + e.getMessage());
        }
    }
}
