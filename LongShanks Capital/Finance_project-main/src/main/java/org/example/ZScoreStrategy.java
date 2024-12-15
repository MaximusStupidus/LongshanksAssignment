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

public class ZScoreStrategy {

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

    private static Map<String, Object> simulate(int zScoreWindow) {
        StockDataManager dataManager = new StockDataManager();
        dataManager.loadHistoricalDataFromCSV("stock_data/consolidated_stock_data.csv");
        List<String> stocks = dataManager.getStocks();

        BigDecimal cash = BigDecimal.valueOf(INITIAL_CAPITAL);
        Map<String, Long> portfolio = new HashMap<>();
        Map<String, BigDecimal> closingPrice = new HashMap<>();
        List<BigDecimal> dailyReturns = new ArrayList<>();
        List<BigDecimal> portfolioValues = new ArrayList<>();
        BigDecimal portfolioValue = cash;

        for (int i = zScoreWindow; i < dataManager.getHistoricalData(stocks.get(0)).size(); i++) {
            Map<String, Integer> signals = new HashMap<>();

            // Generate signals with Z-Score
            for (String stock : stocks) {
                List<StockData> stockData = dataManager.getHistoricalData(stock);
                closingPrice.put(stock, stockData.get(i).getAdjClose());

                BigDecimal mean = calculateMean(stockData, i, zScoreWindow);
                BigDecimal stdDev = calculateStandardDeviation(stockData, i, zScoreWindow, mean);
                BigDecimal price = stockData.get(i).getAdjClose();
                BigDecimal zScore = price.subtract(mean).divide(stdDev, MathContext.DECIMAL128);

                if (zScore.compareTo(BigDecimal.valueOf(-2)) <= 0) {
                    signals.put(stock, 1); // Buy signal
                } else if (zScore.compareTo(BigDecimal.valueOf(2)) >= 0) {
                    signals.put(stock, -1); // Sell signal
                } else {
                    signals.put(stock, 0); // Hold
                }
            }

            // Adjust positions with volatility-based sizing
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
        int minZScoreWindow = 10;
        int maxZScoreWindow = 50;
        int stepZScoreWindow = 5;

        List<Map<String, Object>> results = new ArrayList<>();

        for (int zScoreWindow = minZScoreWindow; zScoreWindow <= maxZScoreWindow; zScoreWindow += stepZScoreWindow) {
            System.out.printf("Testing combination: Z-Score Window = %d%n", zScoreWindow);
            Map<String, Object> result = simulate(zScoreWindow);
            result.put("ZScoreWindow", zScoreWindow);
            results.add(result);
        }

        // Save results to CSV
        try (FileWriter writer = new FileWriter("z_score_results.csv")) {
            writer.append("ZScoreWindow,FinalCapital,MaxDrawdown,SharpeRatio\n");
            for (Map<String, Object> result : results) {
                writer.append(String.format("%d,%.2f,%.6f,%.6f\n",
                        result.get("ZScoreWindow"),
                        result.get("FinalCapital"),
                        result.get("MaxDrawdown"),
                        result.get("SharpeRatio")));
            }
            System.out.println("Results saved to z_score_results.csv");
        } catch (IOException e) {
            System.err.println("Error writing to CSV: " + e.getMessage());
        }
    }
}