import os
import re
import json
from collections import defaultdict, Counter
from typing import Dict, List, Tuple, Optional, Any

def parse_log_line(line: str) -> Optional[Tuple[str, str, Dict[str, str]]]:
    """
    Парсит строку лога в формате:
    COMMAND timestamp [arg1] [arg2] ...
    
    Примеры:
    SESSION_START 30.06.2020_22:59:22
    QS 30.06.2020_23:00:05 {708-� �� 21.03.2020} 24743895 EXPZ_745996 EXPZ_746205
    DOC_OPEN 30.06.2020_23:00:16 24743895 EXPZ_741518
    -12345 EXPZ_745996 EXPZ_746205 ACC_45614  (строки с отрицательным числом)
    24743895 EXPZ_745996 EXPZ_746205 ACC_45614 EXPZ_741518  (строки без явной команды)
    """
    line = line.strip()
    if not line:
        return None
    
    parts = line.split(maxsplit=2)
    if len(parts) < 2:
        return None
    
    # Проверяем, является ли первое слово числом (включая отрицательные)
    first_part = parts[0]
    second_part = parts[1]
    
    # Проверка на число (целое, включая отрицательное)
    is_number = False
    if first_part.lstrip('-').isdigit():
        is_number = True
    
    # Паттерн для timestamp
    is_timestamp_pattern = bool(re.match(r'\d{2}\.\d{2}\.\d{4}_\d{2}:\d{2}:\d{2}', second_part))
    
    if is_number and not is_timestamp_pattern:
        # Это строка вида "24743895 EXPZ_745996..." или "-12345 EXPZ_745996..."
        command = "NUMERIC_COMMAND"
        timestamp = "UNKNOWN_TIMESTAMP"
        args = {'data': line}  # Сохраняем всю строку как данные
        return (command, timestamp, args)
    
    # Нормальная обработка для обычных команд
    command = parts[0]
    timestamp = parts[1]
    
    args = {}
    
    if len(parts) > 2:
        rest = parts[2]
        
        # Для QS: сначала текст запроса в {}, затем список ID
        if command == 'QS':
            # Ищем текст в фигурных скобках
            brace_match = re.match(r'\{([^}]*)\}\s*(.*)', rest)
            if brace_match:
                args['query_text'] = brace_match.group(1)
                rest_after = brace_match.group(2).strip()
                if rest_after:
                    args['document_ids'] = rest_after
            else:
                # Если нет фигурных скобок, возможно весь остаток - ID
                args['document_ids'] = rest
        
        # Для DOC_OPEN: search_id и document_id
        elif command == 'DOC_OPEN':
            doc_parts = rest.split(maxsplit=1)
            if len(doc_parts) == 2:
                args['search_id'] = doc_parts[0]
                args['document_id'] = doc_parts[1]
            elif len(doc_parts) == 1:
                args['search_id'] = doc_parts[0]
        
        # Для CARD_SEARCH_START: параметры поиска
        elif command == 'CARD_SEARCH_START':
            args['params'] = rest
        
        # Для других команд сохраняем остаток как есть
        else:
            # Проверяем, не является ли остаток списком ID (без команды)
            if re.match(r'^(-?\d+\s+)?([A-Z]+_\d+\s+)*[A-Z]+_\d+$', rest):
                args['document_ids'] = rest
            else:
                args['data'] = rest
    
    return (command, timestamp, args)

def detect_value_type(value: str) -> str:
    """Определяет тип значения аргумента."""
    if not value:
        return "empty"
    
    # ID документа вида БАЗА_НОМЕР
    if re.match(r'^[A-Z]+_\d+$', value):
        return "doc_id"
    
    # ID поиска (обычно длинное число)
    if value.isdigit() and len(value) >= 8:
        return "search_id"
    
    # Целое число
    if value.isdigit():
        return "integer"
    
    # Список ID через пробел
    if ' ' in value and re.match(r'^([A-Z]+_\d+\s+)*[A-Z]+_\d+$', value.strip()):
        return "doc_id_list"
    
    # Текст запроса (может содержать любые символы)
    if re.search(r'[а-яА-Я]', value) or re.search(r'[{}<>]', value):
        return "query_text"
    
    return "string"

def analyze_file(filepath: str, stats: Dict[str, Any]) -> int:
    """Анализирует один файл и возвращает количество обработанных событий."""
    events_count = 0
    try:
        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
            for line_num, line in enumerate(f, 1):
                parsed = parse_log_line(line)
                if not parsed:
                    continue
                
                command, timestamp, args = parsed
                events_count += 1
                
                # Инициализация структуры для команды
                if command not in stats['commands']:
                    stats['commands'][command] = {
                        'total_occurrences': 0,
                        'arg_counts': Counter(),
                        'arg_names': set(),
                        'arg_types': defaultdict(lambda: Counter())
                    }
                
                cmd_stats = stats['commands'][command]
                cmd_stats['total_occurrences'] += 1
                cmd_stats['arg_counts'][len(args)] += 1
                
                # Анализ аргументов
                for arg_name, arg_value in args.items():
                    cmd_stats['arg_names'].add(arg_name)
                    arg_type = detect_value_type(arg_value)
                    cmd_stats['arg_types'][arg_name][arg_type] += 1
                    
                    # Глобальная статистика
                    stats['global_value_types'][arg_type] += 1
                    stats['global_total_values'] += 1
                
                # Статистика по временным меткам (формат даты)
                if timestamp != "UNKNOWN_TIMESTAMP":
                    time_parts = timestamp.split('_')
                    if len(time_parts) == 2:
                        date_part, time_part = time_parts
                        stats['date_formats'][date_part] += 1
                
    except Exception as e:
        print(f"Ошибка при чтении {filepath}: {e}")
    
    return events_count

def main():
    data_dir = "./data/"
    if not data_dir:
        data_dir = '.'
    
    if not os.path.isdir(data_dir):
        print(f"Директория {data_dir} не существует!")
        return
    
    stats = {
        'commands': {},
        'global_total_events': 0,
        'global_total_values': 0,
        'global_value_types': Counter(),
        'date_formats': Counter()
    }
    
    files_processed = 0
    total_events = 0
    
    print("\nПоиск файлов...")
    
    for i in range(10000):
        filename = str(i)
        filepath = os.path.join(data_dir, filename)
        
        if os.path.isfile(filepath):
            events = analyze_file(filepath, stats)
            if events > 0:
                files_processed += 1
                total_events += events
                if files_processed % 100 == 0:
                    print(f"  Обработано {files_processed} файлов, найдено событий: {total_events}")
    
    print(f"\n{'='*80}")
    print(f"РЕЗУЛЬТАТЫ АНАЛИЗА")
    print(f"{'='*80}")
    print(f"Обработано файлов: {files_processed}")
    print(f"Всего событий: {total_events}")
    print(f"Всего значений аргументов: {stats['global_total_values']}")
    
    if total_events == 0:
        print("\n⚠️ Не найдено ни одного события в файлах!")
        print("Проверьте:")
        print("  1. Правильно ли указан путь к директории")
        print("  2. Есть ли файлы с именами от 0 до 9999 (без расширения)")
        print("  3. Соответствует ли формат файлов ожидаемому")
        return
    
    print(f"\n{'='*80}")
    print("СТАТИСТИКА ПО КОМАНДАМ:")
    print(f"{'='*80}\n")
    
    for command, cmd_stats in sorted(stats['commands'].items()):
        print(f"📌 КОМАНДА: {command}")
        print(f"   Всего вхождений: {cmd_stats['total_occurrences']}")
        
        if cmd_stats['arg_counts']:
            print(f"   Распределение по количеству аргументов:")
            for num_args, count in sorted(cmd_stats['arg_counts'].items()):
                percentage = count * 100 // cmd_stats['total_occurrences'] if cmd_stats['total_occurrences'] > 0 else 0
                print(f"     - {num_args} аргумент(ов): {count} раз ({percentage}%)")
        
        if cmd_stats['arg_names']:
            print(f"   Аргументы ({len(cmd_stats['arg_names'])}):")
            for arg_name in sorted(cmd_stats['arg_names']):
                types_counter = cmd_stats['arg_types'].get(arg_name, {})
                total_for_arg = sum(types_counter.values())
                print(f"     • {arg_name}:")
                for val_type, type_count in sorted(types_counter.items(), key=lambda x: -x[1]):
                    percentage = type_count * 100 // total_for_arg if total_for_arg > 0 else 0
                    print(f"         - {val_type}: {type_count} ({percentage}%)")
        print()
    
    print(f"\n{'='*80}")
    print("ГЛОБАЛЬНАЯ СТАТИСТИКА ТИПОВ ЗНАЧЕНИЙ:")
    print(f"{'='*80}")
    for val_type, count in sorted(stats['global_value_types'].items(), key=lambda x: -x[1]):
        percentage = count * 100 / stats['global_total_values'] if stats['global_total_values'] else 0
        print(f"  {val_type:20}: {count:8} ({percentage:5.2f}%)")
    
    if stats['date_formats']:
        print(f"\n{'='*80}")
        print("ВСТРЕЧАЮЩИЕСЯ ДАТЫ (первые 10):")
        print(f"{'='*80}")
        for date, count in stats['date_formats'].most_common(10):
            print(f"  {date}: {count} раз")
    
    # Сохраняем JSON
    output_file = "log_analysis_stats.json"
    serializable_stats = {
        'total_events': total_events,
        'total_values': stats['global_total_values'],
        'global_value_types': dict(stats['global_value_types']),
        'date_formats': dict(stats['date_formats'].most_common(20)),
        'commands': {}
    }
    
    for cmd, data in stats['commands'].items():
        serializable_stats['commands'][cmd] = {
            'total_occurrences': data['total_occurrences'],
            'arg_counts': dict(data['arg_counts']),
            'arg_names': list(data['arg_names']),
            'arg_types': {
                arg_name: dict(types) for arg_name, types in data['arg_types'].items()
            }
        }
    
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(serializable_stats, f, indent=2, ensure_ascii=False)
    
    print(f"\n✅ Детальная статистика сохранена в файл: {output_file}")

if __name__ == "__main__":
    main()