package org.example;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PairsTrading {
    private static final double INITIAL_CAPITAL = 1_000_000.0;
    private static final double THRESHOLD = 1.0; // Z-score entry threshold

    private static BigDecimal calculateZScore(BigDecimal spread, BigDecimal mean, BigDecimal stdDev) {
        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO; // Avoid division by zero
        }
        return spread.subtract(mean).divide(stdDev, MathContext.DECIMAL128);
    }

    public static double calculateMaxDrawdown(List<Double> portfolioValues) {
        double maxDrawdown = 0.0;
        double peak = portfolioValues.get(0);

        for (double value : portfolioValues) {
            peak = Math.max(peak, value);
            double currentDrawdown = (peak - value) / peak;
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

    private static SimulationResult simulatePairsTrading(List<StockData> stockDataMA, List<StockData> stockDataV, int window) {
        Map<String, BigDecimal> portfolio = new HashMap<>();
        portfolio.put("MA", BigDecimal.ZERO);
        portfolio.put("V", BigDecimal.ZERO);

        BigDecimal capital = BigDecimal.valueOf(INITIAL_CAPITAL);
        List<Double> portfolioValues = new ArrayList<>();
        List<BigDecimal> dailyReturns = new ArrayList<>();

        // Rolling statistics
        for (int i = window; i < stockDataMA.size(); i++) {
            List<StockData> windowMA = stockDataMA.subList(i - window, i);
            List<StockData> windowV = stockDataV.subList(i - window, i);

            // Calculate spread, mean, and stdDev
            BigDecimal spread = stockDataMA.get(i).getAdjClose().subtract(stockDataV.get(i).getAdjClose());
            BigDecimal mean = windowMA.stream()
                    .map(data -> data.getAdjClose().subtract(windowV.get(windowMA.indexOf(data)).getAdjClose()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(window), MathContext.DECIMAL128);
            BigDecimal variance = windowMA.stream()
                    .map(data -> data.getAdjClose().subtract(windowV.get(windowMA.indexOf(data)).getAdjClose()).subtract(mean).pow(2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(window), MathContext.DECIMAL128);
            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

            BigDecimal zScore = calculateZScore(spread, mean, stdDev);
            BigDecimal maPrice = stockDataMA.get(i).getAdjClose();
            BigDecimal vPrice = stockDataV.get(i).getAdjClose();

            // Generate trading signals based on z-score
            if (zScore.compareTo(BigDecimal.valueOf(-THRESHOLD)) < 0) {
                // Long MA, Short V
                BigDecimal maSharesToBuy = capital.divide(maPrice.multiply(BigDecimal.valueOf(2)), MathContext.DECIMAL128);
                BigDecimal vSharesToShort = capital.divide(vPrice.multiply(BigDecimal.valueOf(2)), MathContext.DECIMAL128);

                portfolio.put("MA", portfolio.get("MA").add(maSharesToBuy));
                portfolio.put("V", portfolio.get("V").subtract(vSharesToShort));
                capital = capital.subtract(maSharesToBuy.multiply(maPrice)).add(vSharesToShort.multiply(vPrice));
            } else if (zScore.compareTo(BigDecimal.valueOf(THRESHOLD)) > 0) {
                // Short MA, Long V
                BigDecimal maSharesToSell = portfolio.get("MA");
                BigDecimal vSharesToBuy = portfolio.get("V").abs();

                portfolio.put("MA", portfolio.get("MA").subtract(maSharesToSell));
                portfolio.put("V", portfolio.get("V").add(vSharesToBuy));
                capital = capital.add(maSharesToSell.multiply(maPrice)).subtract(vSharesToBuy.multiply(vPrice));
            }

            // Calculate portfolio value
            BigDecimal portfolioValue = capital.add(
                    portfolio.get("MA").multiply(maPrice)).add(
                    portfolio.get("V").multiply(vPrice));
            portfolioValues.add(portfolioValue.doubleValue());

            // Calculate daily return
            if (portfolioValues.size() > 1) {
                double prevValue = portfolioValues.get(portfolioValues.size() - 2);
                double currValue = portfolioValues.get(portfolioValues.size() - 1);
                dailyReturns.add(BigDecimal.valueOf((currValue - prevValue) / prevValue));
            }
        }

        // Calculate performance metrics
        double maxDrawdown = calculateMaxDrawdown(portfolioValues);
        BigDecimal avgReturn = dailyReturns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal variance = dailyReturns.stream()
                .map(r -> r.subtract(avgReturn).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(dailyReturns.size()), MathContext.DECIMAL128);
        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal sharpeRatio = stdDev.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : avgReturn.divide(stdDev, MathContext.DECIMAL128).multiply(BigDecimal.valueOf(Math.sqrt(252)));

        return new SimulationResult(portfolioValues.get(portfolioValues.size() - 1), maxDrawdown, sharpeRatio.doubleValue());
    }

    public static void main(String[] args) {
        StockDataManager dataManager = new StockDataManager();
        dataManager.loadHistoricalDataFromCSV("/Users/ojasjain/Desktop/Internships/LongShanks Capital/Finance_project-main/stock_data/consolidated_stock_data.csv");

        List<StockData> stockDataMA = dataManager.getHistoricalData("MA");
        List<StockData> stockDataV = dataManager.getHistoricalData("V");

        int rollingWindow = 20;
        SimulationResult result = simulatePairsTrading(stockDataMA, stockDataV, rollingWindow);

        System.out.println("Pairs Trading Results:");
        System.out.println(result);
    }
}
