package com.nemo.weather;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    // 城市名 / 中国天气网城市编码
    private static final String[] NAMES = {
            "北京", "上海", "广州", "深圳", "天津", "重庆", "杭州", "南京", "苏州", "无锡",
            "宁波", "温州", "成都", "武汉", "西安", "郑州", "长沙", "沈阳", "大连", "青岛",
            "济南", "厦门", "福州", "合肥", "南昌", "昆明", "贵阳", "南宁", "海口", "太原",
            "石家庄", "哈尔滨", "长春", "兰州", "西宁", "银川", "乌鲁木齐", "呼和浩特", "拉萨",
            "佛山", "东莞", "珠海", "中山", "惠州", "汕头", "泉州", "烟台", "潍坊", "徐州",
            "洛阳", "唐山", "保定"
    };
    private static final String[] CODES = {
            "101010100", "101020100", "101280101", "101280601", "101030100", "101040100",
            "101210101", "101190101", "101190401", "101190201",
            "101210401", "101210701", "101270101", "101200101", "101110101", "101180101",
            "101250101", "101070101", "101070201", "101120201",
            "101120101", "101230201", "101230101", "101220101", "101240101", "101290101",
            "101260101", "101300101", "101310101", "101100101",
            "101090101", "101050101", "101060101", "101160101", "101150101", "101170101",
            "101130101", "101080101", "101140101",
            "101280800", "101281601", "101280701", "101281701", "101280301", "101280501",
            "101230501", "101120501", "101120601", "101190801",
            "101180901", "101090501", "101090201"
    };

    private TextView cur;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        cur = (TextView) findViewById(R.id.cur);
        ListView list = (ListView) findViewById(R.id.list);

        final SharedPreferences sp = getSharedPreferences(WeatherWidget.PREFS, MODE_PRIVATE);
        showCurrent(sp);

        list.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, NAMES));

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                sp.edit()
                        .putString(WeatherWidget.K_CODE, CODES[pos])
                        .putString(WeatherWidget.K_NAME, NAMES[pos])
                        .commit();
                showCurrent(sp);
                WeatherWidget.refreshAsync(MainActivity.this);
                Toast.makeText(MainActivity.this, "已切换到 " + NAMES[pos], Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private void showCurrent(SharedPreferences sp) {
        String n = sp.getString(WeatherWidget.K_NAME, WeatherWidget.DEF_NAME);
        cur.setText("当前：" + n + "   （点下面的城市即可切换，桌面卡片会立刻刷新）");
    }
}
