<?php
// 最小 POST 探针
header('Content-Type: application/json');
echo json_encode([
    'method' => $_SERVER['REQUEST_METHOD'] ?? null,
    'ct'     => $_SERVER['CONTENT_TYPE'] ?? null,
    'cl'     => $_SERVER['CONTENT_LENGTH'] ?? null,
    'uri'    => $_SERVER['REQUEST_URI'] ?? null,
    'pi'     => $_SERVER['PATH_INFO'] ?? null,
    'raw'    => file_get_contents('php://input'),
], JSON_UNESCAPED_UNICODE);
