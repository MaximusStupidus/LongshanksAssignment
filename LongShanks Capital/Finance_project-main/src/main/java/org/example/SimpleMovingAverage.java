package org.example;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class SimpleMovingAverage {
    private static final double INITIAL_CAPITAL = 1_000_000.0;

    private static BigDecimal calculateMovingAverage(List<StockData> stockData, int endIndex, int window) {
        return stockData.stream()
                .map(StockData::getAdjClose)
                .skip(endIndex - window)
                .limit(window)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MathContext.DECIMAL128);
    }

    public static double calculateMaxDrawdown(List<Double> portfolioValues) {
        double maxDrawdown = 0.0;
        double peak = portfolioValues.get(0);

        for (int i = 1; i < portfolioValues.size(); i++) {
            peak = Math.max(peak, portfolioValues.get(i));
            double currentDrawdown = (peak - portfolioValues.get(i)) / peak;
            maxDrawdown = Math.max(maxDrawdown, currentDrawdown);
        }

        return maxDrawdown;
    }

    public static class SimulationResult {
        public double finalCapital;
        public double maxDrawdown;
        public double sharpeRatio;

        public SimulationResult(double finalCapital, double maxDrawdown, double sharpeRatio) {
            this.finalCapital = finalCapital;
            this.maxDrawdown = maxDrawdown;
            this.sharpeRatio = sharpeRatio;
        }

        @Override
        public String toString() {
            return String.format("FinalCapital = %.2f, MaxDrawdown = %.6f, SharpeRatio = %.6f", finalCapital, maxDrawdown, sharpeRatio);
        }
    }

    private static SimulationResult simulate(int shortWindow, int longWindow) {
        StockDataManager dataManager = new StockDataManager();
        dataManager.loadHistoricalDataFromCSV("stock_data/consolidated_stock_data.csv");
        List<String> Stocks = dataManager.getStocks();

        Map<String, Long> Portfolio = new HashMap<>();
        Map<String, BigDecimal> Capital = new HashMap<>();
        Map<String, BigDecimal> ClosingPrice = new HashMap<>();
        List<BigDecimal> dailyReturns = new ArrayList<>(Stream.generate(() -> BigDecimal.ZERO)
                .limit(dataManager.getHistoricalData(Stocks.get(0)).size() - longWindow)
                .toList());

        for (String stock : Stocks) {
            BigDecimal capital = BigDecimal.valueOf(INITIAL_CAPITAL / Stocks.size());

            Portfolio.put(stock, 0L);
            List<StockData> stockData = dataManager.getHistoricalData(stock);

            for (int i = longWindow; i < stockData.size(); i++) {
                BigDecimal shortAverage = calculateMovingAverage(stockData, i, shortWindow);
                BigDecimal longAverage = calculateMovingAverage(stockData, i, longWindow);
                BigDecimal closingPrice = stockData.get(i).getAdjClose();

                if (shortAverage.compareTo(longAverage) > 0) {
                    long portfolio = Portfolio.get(stock);
                    long bought = Math.min(capital.divideToIntegralValue(closingPrice).longValue(), stockData.get(i).getVolume());

                    Portfolio.put(stock, portfolio + bought);
                    capital = capital.subtract(closingPrice.multiply(new BigDecimal(bought)));
                } else if (shortAverage.compareTo(longAverage) < 0) {
                    long portfolio = Portfolio.get(stock);
                    long sold = Math.min(portfolio, stockData.get(i).getVolume());

                    Portfolio.put(stock, portfolio - sold);
                    capital = capital.add(closingPrice.multiply(new BigDecimal(sold)));
                }

                BigDecimal newPortfolioValue = capital.add(stockData.get(i).getAdjClose().multiply(BigDecimal.valueOf(Portfolio.get(stock))));
                ClosingPrice.put(stock, closingPrice);
                dailyReturns.set(i - longWindow, dailyReturns.get(i - longWindow).add(newPortfolioValue));
            }

            Capital.put(stock, capital);
        }

        double maxDrawdown = calculateMaxDrawdown(dailyReturns.stream().map(BigDecimal::doubleValue).toList());

        for (int i = dailyReturns.size() - 1; i > 0; i--) {
            dailyReturns.set(i, dailyReturns.get(i).subtract(dailyReturns.get(i - 1)).divide(dailyReturns.get(i - 1), MathContext.DECIMAL128));
        }
        dailyReturns.set(0, BigDecimal.ZERO);

        BigDecimal finalCapital = new BigDecimal(0);
        for (Map.Entry<String, BigDecimal> entry : Capital.entrySet()) {
            finalCapital = finalCapital.add(entry.getValue());
            finalCapital = finalCapital.add(BigDecimal.valueOf(Portfolio.get(entry.getKey())).multiply(ClosingPrice.get(entry.getKey())));
        }

        BigDecimal averageReturn = dailyReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal variance = dailyReturns.stream()
                .map(r -> r.subtract(averageReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal standardDeviation = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal sharpeRatio = averageReturn.divide(standardDeviation, MathContext.DECIMAL128);

        return new SimulationResult(finalCapital.doubleValue(), maxDrawdown, sharpeRatio.doubleValue() * Math.sqrt(252));
    }

    private static void simulateAndStoreResults(int shortWindow, int longWindow, List<String> results) {
        SimulationResult result = simulate(shortWindow, longWindow);
        results.add(String.format("shortWindow = %d, longWindow = %d, %s", shortWindow, longWindow, result.toString()));
    }

    public static void main(String[] args) {
        int minShortWindow = 5;
        int maxShortWindow = 50;
        int stepShortWindow = 5;

        int minLongWindow = 20;
        int maxLongWindow = 200;
        int stepLongWindow = 10;

        List<String> results = new ArrayList<>();

        for (int shortWindow = minShortWindow; shortWindow <= maxShortWindow; shortWindow += stepShortWindow) {
            for (int longWindow = minLongWindow; longWindow <= maxLongWindow; longWindow += stepLongWindow) {
                if (shortWindow >= longWindow) {
                    continue;
                }
                System.out.printf("Testing combination: shortWindow = %d, longWindow = %d%n", shortWindow, longWindow);
                simulateAndStoreResults(shortWindow, longWindow, results);
            }
        }

        System.out.println("Performance Metrics:");
        for (String result : results) {
            System.out.println(result);
        }

        // Save results to a CSV file
        try (FileWriter writer = new FileWriter("results.csv")) {
            writer.append("ShortWindow,LongWindow,FinalCapital,MaxDrawdown,SharpeRatio\n");
            for (String result : results) {
                String formattedResult = result.replaceAll("FinalCapital = ", "")
                                               .replaceAll("MaxDrawdown = ", "")
                                               .replaceAll("SharpeRatio = ", "")
                                               .replaceAll(", ", ",")
                                               .replace("shortWindow =", "")
                                               .replace("longWindow =", "");
                writer.append(formattedResult).append("\n");
            }
            System.out.println("Results saved to results.csv");
        } catch (IOException e) {
            System.err.println("Error writing to CSV file: " + e.getMessage());
        }
    }
}
