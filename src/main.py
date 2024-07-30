# -*- coding:utf-8 -*-
# @FileName :main.py
# @Versions :1.0.0

import glob
import csv

from tqdm import tqdm







def main():
    """
    主函数
    :return:
    """
    print("*" * 50)
    print("*" * 50)
    # 匹配文件名以 "slime" 开头并以 ".csv" 结尾的文件
    csv_files = glob.glob("slime*.csv")
    if not csv_files:
        print("未找到任何 CSV 文件")
        return


    if len(csv_files) > 1:

        print("找到多个 CSV 文件，请选择要处理的文件:")
        for i in range(len(csv_files)):
            print(f"{i+1}. {csv_files[i]}")

            # 三次输入机会
        for _ in range(3):
            try:
                csv_num = int(input("请输入要处理的文件的编号: "))

            # 非数字
            except ValueError:
                continue

            # 无效数字
            if  csv_num < 1 or csv_num > len(csv_files)+1:
                print("无效的文件编号")
                continue
            else:
                file = csv_files[csv_num - 1]
                break
        else:
            # 如果输入的编号多次无效，则退出程序
            return





    print(f"处理文件: {file}")

    rows = []
    third_column_values = []
    # 先读取文件并将数据存入内存
    with open(file, newline='', encoding='utf-8') as csvfile:
        csvreader = csv.reader(csvfile)
        header = next(csvreader)  # 读取表头
        print(header[3])
        for row in tqdm(csvreader, desc=f"正在加载"):
            rows.append(row)


            third_column_values.append(int(row[2]))
            #
            # print(f"行数据,最大值: {max(third_column_values)}, 最小值: {min(third_column_values)}")
            # input()
        if third_column_values:
            max_value = max(third_column_values)
            min_value = min(third_column_values)
            print(f"加载完毕，共 {len(rows) - 1} 行数据,最大值: {max_value}, 最小值: {min_value}")
        else:
            print(f"加载完毕，共 {len(rows) - 1} 行数据,未找到有效的数值")
            return


    # 文件读取完毕，关闭文件



    while True:
        print('='*30)
        print("1. 换文件")
        print("0. 退出")

        function = input('选择功能:')

        if function == '0':
            return
        elif function == '1':
            return main()
        else:
            print("无效的功能")








if __name__ == '__main__':

    main()
