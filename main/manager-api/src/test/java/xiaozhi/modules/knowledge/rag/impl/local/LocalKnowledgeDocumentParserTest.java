package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class LocalKnowledgeDocumentParserTest {
    private final LocalKnowledgeDocumentParser parser =
            new LocalKnowledgeDocumentParser(new ObjectMapper(), 1200);

    @Test
    void convertsExcelRowsToDeterministicChunks() throws Exception {
        byte[] workbook = workbookWithQaRows();

        LocalKnowledgeDocumentParser.ParsedDocument parsed = parser.parse(
                "产品知识.xlsx", new ByteArrayInputStream(workbook));

        assertTrue(parsed.structured());
        assertEquals(2, parsed.directChunks().size());
        assertEquals("如何保存？", parsed.directChunks().get(0).question());
        assertEquals("置于阴凉处", parsed.directChunks().get(0).answer());
        assertTrue(parsed.directChunks().get(0).content().contains("来源：产品问答 第2行"));
        assertTrue(parsed.modelInput().contains("产品：白酒"));
    }

    @Test
    void convertsJsonAndCsvWithoutModelOutput() throws Exception {
        String json = "[{\"question\":\"怎么喝\",\"answer\":\"适量饮用\",\"category\":\"提示\"}]";
        LocalKnowledgeDocumentParser.ParsedDocument jsonResult = parser.parse(
                "faq.json", new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        String csv = "question,answer,category\n怎么存,避光保存,说明\n";
        LocalKnowledgeDocumentParser.ParsedDocument csvResult = parser.parse(
                "faq.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertEquals("怎么喝", jsonResult.directChunks().get(0).question());
        assertEquals("适量饮用", jsonResult.directChunks().get(0).answer());
        assertEquals("怎么存", csvResult.directChunks().get(0).question());
        assertEquals("避光保存", csvResult.directChunks().get(0).answer());
    }

    static byte[] workbookWithQaRows() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("产品问答");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("问题");
            header.createCell(1).setCellValue("答案");
            header.createCell(2).setCellValue("产品");
            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("如何保存？");
            first.createCell(1).setCellValue("置于阴凉处");
            first.createCell(2).setCellValue("白酒");
            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("建议饮用温度？");
            second.createCell(1).setCellValue("常温");
            second.createCell(2).setCellValue("黄酒");
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
